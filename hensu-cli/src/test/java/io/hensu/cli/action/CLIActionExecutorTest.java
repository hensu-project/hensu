package io.hensu.cli.action;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.execution.action.ActionHandler;
import io.hensu.core.execution.action.CommandRegistry;
import io.hensu.core.execution.action.CommandRegistry.CommandDefinition;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CLIActionExecutorTest {

    private CLIActionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CLIActionExecutor();
    }

    // ========== Send Action Tests ==========

    @Test
    void shouldResolveTemplateVariablesInPayload() {
        var handler = new PayloadCapturingHandler("template-handler");
        executor.registerHandler(handler);

        Action.Send send = new Action.Send("template-handler", Map.of("message", "Hello {name}"));
        Map<String, Object> context = Map.of("name", "World");

        executor.execute(send, context);

        assertThat(handler.capturedPayload).containsEntry("message", "Hello World");
    }

    @Test
    void shouldFailSendWhenHandlerNotRegistered() {
        Action.Send send = new Action.Send("unknown-handler");

        ActionResult result = executor.execute(send, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("unknown-handler");
    }

    // ========== Execute Action – Security Tests ==========

    @Test
    void shouldEscapeContextValuesToPreventShellInjection() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("greet", new CommandDefinition("echo {name}"));
        executor.setCommandRegistry(registry);

        // Malicious context value attempting command injection
        Action.Execute exec = new Action.Execute("greet");
        Map<String, Object> context = Map.of("name", "'; rm -rf / ; echo '");

        ActionResult result = executor.execute(exec, context);

        assertThat(result.success()).isTrue();
        // The injected command should appear as literal text, not execute
        assertThat(result.output().toString()).contains("rm -rf");
    }

    @Test
    void shouldEscapeBackticksInContextValues() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("show", new CommandDefinition("echo {val}"));
        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("show");
        Map<String, Object> context = Map.of("val", "$(whoami)");

        ActionResult result = executor.execute(exec, context);

        assertThat(result.success()).isTrue();
        // Should output the literal string, not the result of whoami
        assertThat(result.output().toString()).contains("$(whoami)");
    }

    @Test
    void shouldEscapeDollarExpansionInContextValues() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("show", new CommandDefinition("echo {val}"));
        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("show");
        Map<String, Object> context = Map.of("val", "${HOME}");

        ActionResult result = executor.execute(exec, context);

        assertThat(result.success()).isTrue();
        assertThat(result.output().toString()).contains("${HOME}");
    }

    // ========== Execute Action – Timeout Tests ==========

    @Test
    void shouldTimeoutHangingProcess() {
        CommandRegistry registry = new CommandRegistry();
        // 100ms timeout with a command that sleeps for 10s
        registry.registerCommand("hang", new CommandDefinition("sleep 10", 100));
        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("hang");

        ActionResult result = executor.execute(exec, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("timed out");
    }

    @Test
    void shouldTimeoutProcessThatFloodsPipeBuffer() {
        CommandRegistry registry = new CommandRegistry();
        // Generates output exceeding typical 64KB pipe buffer, with 200ms timeout
        registry.registerCommand(
                "flood", new CommandDefinition("yes 'aaaaaaaaaa' | head -100000; sleep 10", 200));
        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("flood");

        ActionResult result = executor.execute(exec, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("timed out");
    }

    // ========== Execute Action – Functional Tests ==========

    @Test
    void shouldExecuteRegisteredCommand() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("echo-test", new CommandDefinition("echo 'Hello World'"));
        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("echo-test");

        ActionResult result = executor.execute(exec, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output().toString()).contains("Hello World");
    }

    @Test
    void shouldReturnFailureOnNonZeroExitCode() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("fail-cmd", new CommandDefinition("exit 1"));
        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("fail-cmd");

        ActionResult result = executor.execute(exec, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("exit code");
    }

    @Test
    void shouldFailWhenCommandNotInRegistry() {
        Action.Execute exec = new Action.Execute("unknown-command");

        ActionResult result = executor.execute(exec, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Command not found");
    }

    // ========== Test Helpers ==========

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
