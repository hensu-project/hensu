package io.hensu.cli.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.hensu.core.execution.action.CommandDefinition;
import io.hensu.core.execution.action.SandboxPolicy;
import io.hensu.core.tool.ToolCallStatus;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/// Verifies that bubblewrap actually contains a process tree on this host.
///
/// The security model's claim is not that the runner asks for containment but
/// that containment happens, so every assertion here is about what a real process
/// could not do. The tests are assumption-gated on the backend's probe rather than
/// failed when it is absent: a kernel with unprivileged user namespaces disabled –
/// a hardened host, some container runtimes – cannot run them, and a red build
/// there would say nothing about the code.
///
/// @see SeatbeltProfileTest for the macOS half of the same contract
@EnabledOnOs(OS.LINUX)
class SandboxContainmentTest {

    private static final String BASH = "/bin/bash";

    @TempDir Path workingDir;
    @TempDir Path cacheDir;

    private CommandRunner runner;

    @BeforeEach
    void setUp() {
        SandboxLauncher launcher = new BwrapSandboxLauncher();
        assumeTrue(
                launcher.isAvailable(),
                () -> "bubblewrap is not usable on this host: " + launcher.unavailabilityReason());
        runner = new CommandRunner(launcher, Map.of("PATH", System.getenv("PATH")), false);
    }

    @Test
    void shouldLetAnOrdinaryCommandSeeEnoughOfTheHostToRun() {
        CommandResult result =
                run(
                        "cat /etc/hostname > /dev/null && ls /usr/lib > /dev/null && echo ok",
                        SandboxPolicy.restrictive());

        assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(result.output()).isEqualTo("ok");
    }

    @Test
    void shouldBlockWritesOutsideTheDeclaredSubtrees() throws IOException {
        Files.writeString(workingDir.resolve("untouchable.txt"), "original");

        CommandResult result =
                run(
                        "echo overwritten > untouchable.txt && echo wrote || echo blocked",
                        new SandboxPolicy(false, List.of("build"), List.of()));

        assertThat(result.output()).doesNotContain("wrote");
        assertThat(lastLine(result)).isEqualTo("blocked");
        assertThat(workingDir.resolve("untouchable.txt")).hasContent("original");
    }

    @Test
    void shouldAllowWritesInsideADeclaredSubtreeItHadToCreate() {
        CommandResult result =
                run(
                        "echo produced > build/reports/out.txt && cat build/reports/out.txt",
                        new SandboxPolicy(false, List.of("build/reports"), List.of()));

        assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(result.output()).isEqualTo("produced");
    }

    @Test
    void shouldKeepTheCatalogReadOnlyEvenWhenTheWholeProjectIsWritable() throws IOException {
        Files.writeString(workingDir.resolve("commands.yaml"), "commands: {}\n");

        CommandResult result =
                run(
                        "echo sibling > sibling.txt && echo sibling-ok;"
                                + " echo tampered > commands.yaml && echo catalog-writable"
                                + " || echo catalog-protected",
                        new SandboxPolicy(false, List.of("."), List.of()));

        assertThat(result.output()).contains("sibling-ok").contains("catalog-protected");
        assertThat(workingDir.resolve("commands.yaml")).hasContent("commands: {}\n");
    }

    @Test
    void shouldDenyReachingAHostServiceWhenTheNetworkIsNotDeclared() throws IOException {
        assumeTrue(Files.isExecutable(Path.of(BASH)), "these tests need bash for /dev/tcp");
        try (ServerSocket listener =
                new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            String connect =
                    "exec 3<>/dev/tcp/127.0.0.1/"
                            + listener.getLocalPort()
                            + " && echo connected || echo refused";

            CommandResult denied = runBash(connect, SandboxPolicy.restrictive());
            CommandResult allowed = runBash(connect, new SandboxPolicy(true, List.of(), List.of()));

            assertThat(denied.output()).doesNotContain("connected");
            assertThat(lastLine(denied)).isEqualTo("refused");
            assertThat(lastLine(allowed)).isEqualTo("connected");
        }
    }

    @Test
    void shouldTakeBackgroundChildrenDownWithTheSupervisor() throws Exception {
        Path survivor = workingDir.resolve("build/survivor.txt");

        CommandResult result =
                run(
                        "( sleep 1; echo alive > build/survivor.txt ) & sleep 30",
                        new SandboxPolicy(false, List.of("build"), List.of()),
                        300L);

        assertThat(result.status()).isEqualTo(ToolCallStatus.TIMEOUT);
        Thread.sleep(2_000);
        assertThat(survivor).doesNotExist();
    }

    @Test
    void shouldGiveTheCommandAWritableHomeThatDoesNotLeakIntoTheNextCall() {
        String script =
                "echo state > \"$HOME/marker\" && cat \"$HOME/marker\";"
                        + " test -e \"$HOME/marker-from-last-call\""
                        + " && echo leaked || echo hermetic";

        CommandResult first = run(script, SandboxPolicy.restrictive());
        CommandResult second = run(script, SandboxPolicy.restrictive());

        assertThat(first.output()).contains("state").contains("hermetic");
        assertThat(second.output()).contains("state").contains("hermetic");
    }

    @Test
    void shouldMountADeclaredCacheReadWriteWithItsHostContents() throws IOException {
        Files.writeString(cacheDir.resolve("already-downloaded"), "from an earlier run");

        CommandResult result =
                run(
                        "cat '"
                                + cacheDir
                                + "/already-downloaded'"
                                + " && echo fresh > '"
                                + cacheDir
                                + "/new-entry'",
                        new SandboxPolicy(false, List.of(), List.of(cacheDir.toString())));

        assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(result.output()).contains("from an earlier run");
        assertThat(cacheDir.resolve("new-entry")).exists();
    }

    @Test
    void shouldContainARungTheSameWayItContainsAnAuthoredCommand() throws IOException {
        Files.writeString(workingDir.resolve("untouchable.txt"), "original");
        Files.writeString(workingDir.resolve("commands.yaml"), "commands: {}\n");

        CommandResult result =
                runRung(
                        "echo overwritten > untouchable.txt && echo wrote || echo write-blocked;"
                                + " echo tampered > commands.yaml && echo catalog-writable"
                                + " || echo catalog-protected");

        assertThat(result.output()).doesNotContain("wrote").doesNotContain("catalog-writable");
        assertThat(result.output()).contains("write-blocked").contains("catalog-protected");
        assertThat(workingDir.resolve("untouchable.txt")).hasContent("original");
        assertThat(workingDir.resolve("commands.yaml")).hasContent("commands: {}\n");
    }

    @Test
    void shouldDenyARungTheNetworkWithNoWayForConfigurationToGrantIt() throws IOException {
        assumeTrue(Files.isExecutable(Path.of(BASH)), "these tests need bash for /dev/tcp");
        try (ServerSocket listener =
                new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            CommandResult result =
                    runRung(
                            BASH
                                    + " -c 'exec 3<>/dev/tcp/127.0.0.1/"
                                    + listener.getLocalPort()
                                    + "' && echo connected || echo refused");

            assertThat(result.output()).doesNotContain("connected");
            assertThat(lastLine(result)).isEqualTo("refused");
        }
    }

    @Test
    void shouldGiveARungAWritableSubtreeOnlyWhereEveryCommandHasOne() {
        CommandResult result = runRung("echo scratch > \"$HOME/note\" && cat \"$HOME/note\"");

        assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(result.output()).isEqualTo("scratch");
    }

    // ------------------------------------------------------------------ helpers

    /// Runs agent-authored text through a rung, which is the only entry form whose
    /// command line the catalog did not write.
    private CommandResult runRung(String commandLine) {
        return runner.run(
                CommandDefinition.rung("Run one shell command line", 30_000L),
                Map.of(CommandDefinition.RUNG_PARAM, commandLine),
                workingDir);
    }

    /// Returns the final line a command printed.
    ///
    /// {@link CommandResult#output()} is stdout and stderr merged, so a script whose
    /// point is that its first attempt failed carries the shell's diagnostic for that
    /// failure ahead of the branch marker. The marker is what the assertion is about,
    /// and it is always last.
    ///
    /// @param result the command outcome to read, not null
    /// @return the last line of the merged output, never null
    private static String lastLine(CommandResult result) {
        String output = result.output();
        return output.substring(output.lastIndexOf('\n') + 1);
    }

    private CommandResult run(String script, SandboxPolicy policy) {
        return run(script, policy, 30_000L);
    }

    private CommandResult run(String script, SandboxPolicy policy, long timeoutMs) {
        return runner.run(
                new CommandDefinition(
                        null, script, timeoutMs, Map.of(), null, policy, false, false),
                Map.of(),
                workingDir);
    }

    private CommandResult runBash(String script, SandboxPolicy policy) {
        return runner.run(
                new CommandDefinition(
                        List.of(BASH, "-c", script),
                        null,
                        30_000L,
                        Map.of(),
                        null,
                        policy,
                        false,
                        false),
                Map.of(),
                workingDir);
    }
}
