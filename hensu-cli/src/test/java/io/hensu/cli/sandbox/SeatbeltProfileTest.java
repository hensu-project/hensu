package io.hensu.cli.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.hensu.core.execution.action.CommandDefinition;
import io.hensu.core.execution.action.SandboxPolicy;
import io.hensu.core.tool.ToolCallStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/// Covers the macOS backend: its profile on every platform, its containment on macOS.
///
/// The generated SBPL is pure text, so it is checked everywhere – a profile that
/// silently stopped denying would otherwise only be noticed on a Mac. The
/// behavioural half mirrors {@link SandboxContainmentTest} and runs only where
/// `sandbox-exec` exists.
class SeatbeltProfileTest {

    @TempDir Path workingDir;
    @TempDir Path privateHome;

    private final SeatbeltSandboxLauncher launcher = new SeatbeltSandboxLauncher();

    @Test
    void shouldDenyByDefaultAndAllowOnlyTheDeclaredScopes() {
        String profile =
                launcher.buildProfile(
                        new SandboxPolicy(false, List.of("build"), List.of("/opt/cache")),
                        workingDir,
                        privateHome);

        assertThat(profile).startsWith("(version 1)\n(deny default)");
        assertThat(profile).contains("(subpath \"" + workingDir + "\")");
        assertThat(profile).contains("(subpath \"" + workingDir.resolve("build") + "\")");
        assertThat(profile).contains("(subpath \"/opt/cache\")");
        assertThat(profile).contains("(subpath \"" + privateHome + "\")");
        assertThat(profile).doesNotContain("(allow network*)");
    }

    @Test
    void shouldAllowTheNetworkOnlyWhenTheCommandDeclaredIt() {
        String profile =
                launcher.buildProfile(
                        new SandboxPolicy(true, List.of(), List.of()), workingDir, privateHome);

        assertThat(profile).contains("(allow network*)");
    }

    @Test
    void shouldDenyTheCatalogAfterTheWriteAllowancesSoTheDenyWins() {
        String profile =
                launcher.buildProfile(
                        new SandboxPolicy(false, List.of("."), List.of()), workingDir, privateHome);

        int allowWrites = profile.indexOf("(allow file-write*");
        int denyCatalog = profile.indexOf("(deny file-write*");
        assertThat(allowWrites).isNotNegative();
        // SBPL resolves by last match, so the ordering is the guarantee.
        assertThat(denyCatalog).isGreaterThan(allowWrites);
        for (String configFile : CommandRunner.PROTECTED_CONFIG_FILES) {
            assertThat(profile.substring(denyCatalog))
                    .contains("(literal \"" + workingDir.resolve(configFile) + "\")");
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void shouldBlockWritesOutsideTheDeclaredSubtrees() throws IOException {
        assumeTrue(
                launcher.isAvailable(),
                () -> "sandbox-exec is not usable here: " + launcher.unavailabilityReason());
        Files.writeString(workingDir.resolve("untouchable.txt"), "original");
        CommandRunner runner =
                new CommandRunner(launcher, Map.of("PATH", System.getenv("PATH")), false);

        CommandResult result =
                runner.run(
                        new CommandDefinition(
                                null,
                                "echo overwritten > untouchable.txt && echo wrote || echo blocked",
                                30_000L,
                                Map.of(),
                                null,
                                new SandboxPolicy(false, List.of("build"), List.of()),
                                false,
                                false),
                        Map.of(),
                        workingDir);

        assertThat(result.output()).isEqualTo("blocked");
        assertThat(workingDir.resolve("untouchable.txt")).hasContent("original");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void shouldKeepTheCatalogReadOnlyEvenWhenTheWholeProjectIsWritable() throws IOException {
        assumeTrue(
                launcher.isAvailable(),
                () -> "sandbox-exec is not usable here: " + launcher.unavailabilityReason());
        Files.writeString(workingDir.resolve("commands.yaml"), "commands: {}\n");
        CommandRunner runner =
                new CommandRunner(launcher, Map.of("PATH", System.getenv("PATH")), false);

        CommandResult result =
                runner.run(
                        new CommandDefinition(
                                null,
                                "echo sibling > sibling.txt && echo sibling-ok;"
                                        + " echo tampered > commands.yaml && echo catalog-writable"
                                        + " || echo catalog-protected",
                                30_000L,
                                Map.of(),
                                null,
                                new SandboxPolicy(false, List.of("."), List.of()),
                                false,
                                false),
                        Map.of(),
                        workingDir);

        assertThat(result.status()).isNotEqualTo(ToolCallStatus.SANDBOX_UNAVAILABLE);
        assertThat(result.output()).contains("sibling-ok").contains("catalog-protected");
    }
}
