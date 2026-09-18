package io.hensu.core.execution.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Pins the command catalog grammar and every rule it enforces at load time.
///
/// The catalog is the allowlist of the execution security model, so its failures
/// matter as much as its successes: a rule that stops enforcing turns a
/// configuration mistake into an agent-reachable capability. The `bad-*` golden
/// files are that half of the contract, and each asserts the line the operator
/// has to look at.
class CommandRegistryTest {

    @TempDir Path workingDir;

    // ------------------------------------------------------------ valid corpus

    @Test
    void shouldCompileEveryKeyOfTheFullGrammar() {
        CommandRegistry registry = load("valid-full.yaml");

        assertThat(registry.getCommandIds())
                .containsExactlyInAnyOrder(
                        "run-tests", "summarise-history", "archive", "shell-rung");

        CommandDefinition runTests = registry.getCommand("run-tests");
        assertThat(runTests.shellMode()).isFalse();
        assertThat(runTests.execTemplate()).containsExactly("/bin/echo", "test", "{gradle_args}");
        assertThat(runTests.timeoutMs()).isEqualTo(120000L);
        assertThat(runTests.environment()).containsExactly(java.util.Map.entry("CI", "true"));
        assertThat(runTests.unattended()).isTrue();
        assertThat(runTests.approvalRequired()).isFalse();
        assertThat(runTests.sandbox().network()).isFalse();
        assertThat(runTests.sandbox().writePaths()).containsExactly("build/", ".gradle/");
        assertThat(runTests.sandbox().cachePaths())
                .singleElement()
                .asString()
                .endsWith("/.gradle/caches")
                .doesNotStartWith("~");

        ParamSpec gradleArgs = runTests.toolSpec().param("gradle_args").orElseThrow();
        assertThat(gradleArgs.type()).isEqualTo("string");
        assertThat(gradleArgs.required()).isFalse();
        assertThat(gradleArgs.pattern()).isEqualTo("^[-A-Za-z0-9=._:]*$");
        assertThat(gradleArgs.maxLength()).isEqualTo(64);
    }

    @Test
    void shouldCompileShellFormWithSecretAndEnumeratedParameters() {
        CommandDefinition history = load("valid-full.yaml").getCommand("summarise-history");

        assertThat(history.shellMode()).isTrue();
        assertThat(history.shellCommand()).isEqualTo("git log --oneline | head -20");
        assertThat(history.execTemplate()).isNull();
        assertThat(history.approvalRequired()).isTrue();
        assertThat(history.sandbox().network()).isTrue();

        ParamSpec token = history.toolSpec().param("token").orElseThrow();
        assertThat(token.required()).isTrue();
        assertThat(token.secret()).isTrue();
        assertThat(token.environmentVariable()).isEqualTo("HENSU_PARAM_TOKEN");
        assertThat(history.toolSpec().param("mode").orElseThrow().enumValues())
                .containsExactly("fast", "slow");
    }

    @Test
    void shouldCompileARungWithAFixedSchemaAndAnUnwidenablePolicy() {
        CommandDefinition rung = load("valid-full.yaml").getCommand("shell-rung");

        assertThat(rung.rung()).isTrue();
        assertThat(rung.execTemplate()).isNull();
        assertThat(rung.shellCommand()).isNull();
        assertThat(rung.shellMode()).isFalse();
        assertThat(rung.agentVisible()).isTrue();
        assertThat(rung.timeoutMs()).isEqualTo(60000L);
        assertThat(rung.environment()).isEmpty();
        assertThat(rung.sandbox()).isEqualTo(SandboxPolicy.restrictive());

        assertThat(rung.params())
                .singleElement()
                .satisfies(
                        param -> {
                            assertThat(param.name()).isEqualTo(CommandDefinition.RUNG_PARAM);
                            assertThat(param.required()).isTrue();
                            assertThat(param.maxLength())
                                    .isEqualTo(CommandDefinition.RUNG_MAX_LENGTH);
                            assertThat(param.pattern()).isNull();
                            assertThat(param.enumValues()).isEmpty();
                        });
    }

    @Test
    void shouldRefuseARungWidenedByAnythingOtherThanTheCatalog() {
        assertThatThrownBy(
                        () ->
                                new CommandDefinition(
                                        null,
                                        null,
                                        1000L,
                                        java.util.Map.of(),
                                        CommandDefinition.rungToolSpec("anything"),
                                        new SandboxPolicy(
                                                true, java.util.List.of(), java.util.List.of()),
                                        true,
                                        false,
                                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be widened");
    }

    @Test
    void shouldTreatAnEntryThatSaysNothingAboutSupervisionAsUnattended() {
        CommandRegistry registry = load("valid-full.yaml");

        assertThat(registry.getCommand("summarise-history").unattended()).isTrue();
        assertThat(registry.getCommand("archive").unattended()).isTrue();
        assertThat(
                        parse(
                                        """
                                        commands:
                                          deploy:
                                            description: "Irreversible, so it waits for a human"
                                            exec: ["/bin/echo", "deploying"]
                                            unattended: false
                                        """)
                                .getCommand("deploy")
                                .unattended())
                .isFalse();
    }

    @Test
    void shouldApplyClosedDefaultsToAMinimalEntry() {
        CommandDefinition minimal = load("valid-minimal.yaml").getCommand("list-files");

        assertThat(minimal.timeoutMs()).isEqualTo(CommandDefinition.DEFAULT_TIMEOUT_MS);
        assertThat(minimal.agentVisible()).isFalse();
        assertThat(minimal.unattended()).isTrue();
        assertThat(minimal.approvalRequired()).isFalse();
        assertThat(minimal.sandbox()).isEqualTo(SandboxPolicy.restrictive());
    }

    @Test
    void shouldExposeOnlyCommandsThatOptedIntoAToolBlock() {
        CommandRegistry registry = load("valid-full.yaml");
        registry.registerCommand("hidden", CommandDefinition.exec(java.util.List.of("/bin/echo")));

        assertThat(registry.agentVisibleCommands().keySet())
                .containsExactlyInAnyOrder(
                        "run-tests", "summarise-history", "archive", "shell-rung");
    }

    @Test
    void shouldResolveTheExecutableToAnAbsolutePathSoPathCannotSwapIt() {
        CommandRegistry registry =
                CommandRegistry.parse(
                        """
                        commands:
                          say:
                            description: "Say something through the shell"
                            exec: ["sh", "-c", "true"]
                        """,
                        workingDir);

        assertThat(registry.getCommand("say").execTemplate().getFirst())
                .startsWith("/")
                .endsWith("/sh");
    }

    // ----------------------------------------------------- malformed corpus

    @Test
    void shouldRejectOverIndentedEntry() {
        assertRejected("bad-indent.yaml", 3, "expected an indentation of 4 spaces");
    }

    @Test
    void shouldRejectPlaceholderReferencingAnUndeclaredParameter() {
        assertRejected("bad-placeholder.yaml", 3, "references undeclared parameter 'name'");
    }

    @Test
    void shouldRejectPlaceholderSplicedIntoShellText() {
        assertRejected("bad-shell-param.yaml", 4, "splices placeholder '{name}' into shell text");
    }

    @Test
    void shouldRejectParametersThatMapOntoTheSameEnvironmentVariable() {
        assertRejected("bad-env-collision.yaml", 4, "onto the same variable HENSU_PARAM_USER_NAME");
    }

    @Test
    void shouldRejectWritePathEscapingTheWorkingDirectory() {
        assertRejected("bad-write-escape.yaml", 5, "resolves outside the working directory");
    }

    @Test
    void shouldRejectRelativeCachePath() {
        assertRejected("bad-cache-scope.yaml", 5, "which is not absolute");
    }

    @Test
    void shouldRejectMappingNestedBeyondTheSupportedDepth() {
        assertRejected("bad-depth.yaml", 7, "supported depth");
    }

    @Test
    void shouldRejectAnEntryThatStatesNoPurpose() {
        assertRejected("bad-missing-description.yaml", 3, "must declare a description");
    }

    @Test
    void shouldRejectAnEntryWhosePurposeIsBlank() {
        assertRejected("bad-blank-description.yaml", 3, "declares a blank description");
    }

    // ---------------------------------------------------- rules without a file

    @Test
    void shouldRejectTheRemovedSingleStringCommandForm() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        commands:
                                          greet:
                                            description: "Greet the operator"
                                            command: "echo hello"
                                        """))
                .isInstanceOf(CommandConfigException.class)
                .hasMessageContaining("removed single-string command: form");
    }

    @Test
    void shouldRejectAPlaceholderInTheExecutablePosition() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        commands:
                                          greet:
                                            description: "Greet the operator"
                                            exec: ["{binary}", "hello"]
                                            tool:
                                              description: "Greet"
                                              params:
                                                binary: { type: string }
                                        """))
                .isInstanceOf(CommandConfigException.class)
                .hasMessageContaining("the binary must be a literal");
    }

    @Test
    void shouldRejectMoreThanOnePlaceholderInOneArgvElement() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        commands:
                                          greet:
                                            description: "Greet the operator"
                                            exec: ["/bin/echo", "{a}{b}"]
                                            tool:
                                              description: "Greet"
                                              params:
                                                a: { type: string }
                                                b: { type: string }
                                        """))
                .isInstanceOf(CommandConfigException.class)
                .hasMessageContaining("carries 2 placeholders");
    }

    @Test
    void shouldRejectListParameterEmbeddedInAWiderElement() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        commands:
                                          archive:
                                            description: "Archive the named files"
                                            exec: ["/bin/echo", "--files={files}"]
                                            tool:
                                              description: "Archive"
                                              params:
                                                files: { type: list }
                                        """))
                .isInstanceOf(CommandConfigException.class)
                .hasMessageContaining("must occupy its element alone");
    }

    @Test
    void shouldRejectEnvironmentKeysInTheReservedParameterNamespace() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        commands:
                                          greet:
                                            description: "Greet the operator"
                                            exec: ["/bin/echo"]
                                            env:
                                              HENSU_PARAM_TOKEN: "leaked"
                                        """))
                .isInstanceOf(CommandConfigException.class)
                .hasMessageContaining("namespace is reserved for parameters");
    }

    @Test
    void shouldRejectAnExecutableThatIsNotOnPath() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        commands:
                                          greet:
                                            description: "Greet the operator"
                                            exec: ["definitely-not-a-real-binary-9f3a"]
                                        """))
                .isInstanceOf(CommandConfigException.class)
                .hasMessageContaining("is not on PATH");
    }

    @Test
    void shouldRejectCachePathInsideTheWorkingDirectory() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        commands:
                                          build:
                                            description: "Build the project"
                                            exec: ["/bin/echo"]
                                            sandbox:
                                              cache: ["%s"]
                                        """
                                                .formatted(workingDir.resolve("cache"))))
                .isInstanceOf(CommandConfigException.class)
                .hasMessageContaining("inside the working directory; use write:");
    }

    @Test
    void shouldRejectCachePathNamingTheHomeDirectoryItself() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        commands:
                                          build:
                                            description: "Build the project"
                                            exec: ["/bin/echo"]
                                            sandbox:
                                              cache: ["~"]
                                        """))
                .isInstanceOf(CommandConfigException.class)
                .hasMessageContaining("is the home directory itself");
    }

    @Test
    void shouldRejectUnknownKeysRatherThanIgnoreThem() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        commands:
                                          build:
                                            description: "Build the project"
                                            exec: ["/bin/echo"]
                                            sandbox:
                                              networking: true
                                        """))
                .isInstanceOf(CommandConfigException.class)
                .hasMessageContaining("declares unknown key 'networking'");
    }

    // A block mapping reports the line of its first entry rather than of the key
    // above it, so the three rejections below point one line into the offending
    // block. That is where the operator has to look either way.

    @Test
    void shouldRejectARungCarryingASandboxBlock() {
        assertRejected("bad-rung-sandbox.yaml", 9, "runs under the fixed closed policy");
    }

    @Test
    void shouldRejectARungCarryingAnEnvironment() {
        assertRejected("bad-rung-env.yaml", 9, "receives no extra environment");
    }

    @Test
    void shouldRejectARungDeclaringItsOwnParameters() {
        assertRejected("bad-rung-params.yaml", 9, "takes one fixed parameter 'command'");
    }

    @Test
    void shouldRejectARungNoAgentCouldCall() {
        assertRejected("bad-rung-no-tool.yaml", 4, "exists so an agent can call it");
    }

    @Test
    void shouldRejectARungCarryingCommandTextOfItsOwn() {
        assertRejected("bad-rung-exec.yaml", 6, "comes from the agent");
    }

    @Test
    void shouldRejectShellTextThatPipesWithoutAcknowledgingItsStatus() {
        assertRejected("bad-pipeline-unacknowledged.yaml", 6, "reports its last stage's status");
    }

    @Test
    void shouldRejectAMisspelledPipelineAcknowledgement() {
        assertRejected("bad-pipeline-value.yaml", 7, "expected last-stage-status");
    }

    @Test
    void shouldRejectAPipelineAcknowledgementOnAFormThatCannotPipe() {
        assertRejected("bad-pipeline-argv.yaml", 6, "only shell: true text runs a pipeline");
    }

    @Test
    void shouldRejectARungThatAlsoDeclaresShellForm() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        """
                                        commands:
                                          rung:
                                            description: "Two forms at once"
                                            rung: true
                                            shell: true
                                            command: "echo hello"
                                            tool:
                                              description: "Run a command line"
                                        """))
                .isInstanceOf(CommandConfigException.class)
                .hasMessageContaining("exactly one form");
    }

    // ------------------------------------------------------------------ helpers

    private CommandRegistry load(String resource) {
        return CommandRegistry.parse(read(resource), workingDir);
    }

    private CommandRegistry parse(String content) {
        return CommandRegistry.parse(content, workingDir);
    }

    private void assertRejected(String resource, int expectedLine, String messageFragment) {
        CommandConfigException failure =
                catchThrowableOfType(CommandConfigException.class, () -> load(resource));

        assertThat(failure).as("expected %s to be rejected", resource).isNotNull();
        assertThat(failure).hasMessageContaining(messageFragment);
        assertThat(failure.line()).as("reported line for %s", resource).isEqualTo(expectedLine);
    }

    private static String read(String resource) {
        try (InputStream in =
                CommandRegistryTest.class.getResourceAsStream("/commands/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing golden file: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
