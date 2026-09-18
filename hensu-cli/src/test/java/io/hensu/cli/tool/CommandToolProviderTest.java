package io.hensu.cli.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.cli.sandbox.CommandRunner;
import io.hensu.cli.sandbox.UnavailableSandboxLauncher;
import io.hensu.core.execution.action.CommandDefinition;
import io.hensu.core.execution.action.CommandRegistry;
import io.hensu.core.execution.action.ParamSpec;
import io.hensu.core.execution.action.SandboxPolicy;
import io.hensu.core.execution.action.ToolSpec;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandToolProviderTest {

    @TempDir Path workingDirectory;

    private CommandToolProvider provider;

    @BeforeEach
    void setUp() {
        CommandCatalog catalog = new CommandCatalog();
        catalog.setWorkingDirectory(workingDirectory);
        catalog.setRegistry(registry());
        // Containment is covered by SandboxContainmentTest; these tests are about
        // the mapping between a declared entry and the tool seam, so the runner
        // executes on the host without a backend.
        provider =
                new CommandToolProvider(
                        catalog,
                        new CommandRunner(
                                new UnavailableSandboxLauncher("test"),
                                Map.of("PATH", System.getenv("PATH")),
                                true));
    }

    private static CommandRegistry registry() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand(
                "echo-message",
                new CommandDefinition(
                        List.of("/bin/echo", "{message}"),
                        null,
                        5_000L,
                        Map.of(),
                        new ToolSpec(
                                "Echo a message back",
                                List.of(
                                        new ParamSpec(
                                                "message",
                                                "string",
                                                true,
                                                "^[a-z ]+$",
                                                List.of(),
                                                64,
                                                false))),
                        SandboxPolicy.restrictive(),
                        true,
                        false));
        registry.registerCommand(
                "publish-release",
                new CommandDefinition(
                        List.of("/bin/false"),
                        null,
                        5_000L,
                        Map.of(),
                        new ToolSpec(
                                "Publish a release",
                                List.of(
                                        new ParamSpec(
                                                "token", "string", true, null, List.of(), null,
                                                true))),
                        new SandboxPolicy(true, List.of("build/"), List.of()),
                        false,
                        true));
        registry.registerCommand("internal-only", CommandDefinition.exec(List.of("/bin/true")));
        return registry;
    }

    @Nested
    class Visibility {

        @Test
        void shouldPublishOnlyCommandsThatOptedIn() {
            assertThat(provider.tools())
                    .extracting(ToolDefinition::name)
                    .containsExactlyInAnyOrder("echo-message", "publish-release")
                    .doesNotContain("internal-only");
        }

        @Test
        void shouldRefuseToRunACommandThatIsNotAgentVisible() {
            // The entry exists and the action path can run it; reaching it from
            // an agent must look exactly like a tool that was never offered.
            ToolCallResult result = provider.call("internal-only", Map.of(), Map.of());

            assertThat(result.status()).isEqualTo(ToolCallStatus.UNKNOWN_TOOL);
        }
    }

    @Nested
    class SchemaMapping {

        @Test
        void shouldDescribeDeclaredConstraintsSoTheModelCanSatisfyThem() {
            ToolDefinition tool = tool("echo-message");

            assertThat(tool.description()).isEqualTo("Echo a message back");
            assertThat(tool.parameters())
                    .singleElement()
                    .satisfies(
                            parameter -> {
                                assertThat(parameter.name()).isEqualTo("message");
                                assertThat(parameter.required()).isTrue();
                                assertThat(parameter.description())
                                        .contains("^[a-z ]+$")
                                        .contains("64");
                            });
        }

        @Test
        void shouldCarrySecretIntoSensitiveSoTheLoopRedactsTheValue() {
            assertThat(tool("publish-release").parameters())
                    .singleElement()
                    .satisfies(parameter -> assertThat(parameter.sensitive()).isTrue());
        }

        private ToolDefinition tool(String name) {
            return provider.tools().stream()
                    .filter(definition -> definition.name().equals(name))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Nested
    class ResultMapping {

        @Test
        void shouldReturnTheProcessOutputOnSuccess() {
            ToolCallResult result =
                    provider.call("echo-message", Map.of("message", "hello there"), Map.of());

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(result.output()).isEqualTo("hello there");
            assertThat(result.exitCode()).isZero();
        }

        @Test
        void shouldCarryTheExitCodeThroughOnFailure() {
            ToolCallResult result =
                    provider.call("publish-release", Map.of("token", "secret"), Map.of());

            assertThat(result.status()).isEqualTo(ToolCallStatus.FAILURE);
            assertThat(result.exitCode()).isEqualTo(1);
        }

        @Test
        void shouldReportASchemaViolationWithoutLaunchingAnything() {
            // The pattern rejects digits, so the process must never start.
            ToolCallResult result =
                    provider.call("echo-message", Map.of("message", "hello 42"), Map.of());

            assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(result.error()).contains("does not match pattern");
        }
    }

    @Nested
    class Preview {

        @Test
        void shouldResolveTheArgvAReviewerWouldSee() {
            var preview = provider.preview("echo-message", Map.of("message", "hello there"));

            assertThat(preview.argv()).containsExactly("/bin/echo", "hello there");
            assertThat(preview.sandboxSummary()).isEqualTo("network: off, writes: nothing");
            assertThat(preview.unattendedSafe()).isTrue();
        }

        @Test
        void shouldReportTheContainmentAndPolicyOfAGatedEntry() {
            var preview = provider.preview("publish-release", Map.of("token", "secret"));

            assertThat(preview.sandboxSummary()).isEqualTo("network: on, writes: build/");
            assertThat(preview.unattendedSafe()).isFalse();
        }

        @Test
        void shouldExplainWhyACallCannotBePreparedRatherThanShowABlankArgv() {
            var preview = provider.preview("echo-message", Map.of("message", "hello 42"));

            assertThat(preview.argv()).isEmpty();
            assertThat(preview.summary()).contains("cannot be prepared");
        }

        @Test
        void shouldRefuseToDescribeACommandNoAgentCanCall() {
            assertThatThrownBy(() -> provider.preview("internal-only", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
