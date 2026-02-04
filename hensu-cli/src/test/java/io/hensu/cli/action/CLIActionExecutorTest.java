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
    void shouldFailSendWhenHandlerNotRegistered() {
        Action.Send send = new Action.Send("unknown-handler");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(send, context);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Action handler not found");
        assertThat(result.message()).contains("unknown-handler");
    }

    @Test
    void shouldExecuteRegisteredHandler() {
        executor.registerHandler(new TestActionHandler("test-handler", true, "Success"));

        Action.Send send = new Action.Send("test-handler");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(send, context);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("Success");
    }

    @Test
    void shouldPassPayloadToHandler() {
        var handler = new PayloadCapturingHandler("capture-handler");
        executor.registerHandler(handler);

        Action.Send send = new Action.Send("capture-handler", Map.of("key", "value", "num", 42));
        Map<String, Object> context = Map.of();

        executor.execute(send, context);

        assertThat(handler.capturedPayload).containsEntry("key", "value");
        assertThat(handler.capturedPayload).containsEntry("num", 42);
    }

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
    void shouldListRegisteredHandlersOnFailure() {
        executor.registerHandler(new TestActionHandler("handler-a", true, "OK"));
        executor.registerHandler(new TestActionHandler("handler-b", true, "OK"));

        Action.Send send = new Action.Send("missing-handler");
        ActionResult result = executor.execute(send, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("handler-a");
        assertThat(result.message()).contains("handler-b");
    }

    @Test
    void shouldReturnHandlerFailureResult() {
        executor.registerHandler(new TestActionHandler("failing-handler", false, "Handler error"));

        Action.Send send = new Action.Send("failing-handler");
        ActionResult result = executor.execute(send, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("Handler error");
    }

    @Test
    void shouldHandleEmptyPayload() {
        var handler = new PayloadCapturingHandler("empty-handler");
        executor.registerHandler(handler);

        Action.Send send = new Action.Send("empty-handler");
        executor.execute(send, Map.of());

        assertThat(handler.capturedPayload).isEmpty();
    }

    @Test
    void shouldPassContextToHandler() {
        var handler = new ContextCapturingHandler("context-handler");
        executor.registerHandler(handler);

        Action.Send send = new Action.Send("context-handler");
        Map<String, Object> context = Map.of("user", "testUser", "env", "prod");

        executor.execute(send, context);

        assertThat(handler.capturedContext).containsEntry("user", "testUser");
        assertThat(handler.capturedContext).containsEntry("env", "prod");
    }

    // ========== Execute Action Tests ==========

    @Test
    void shouldFailWhenCommandNotInRegistry() {
        Action.Execute exec = new Action.Execute("unknown-command");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(exec, context);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Command not found in registry");
        assertThat(result.message()).contains("unknown-command");
    }

    @Test
    void shouldExecuteRegisteredCommand() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("echo-test", new CommandDefinition("echo 'Hello World'"));
        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("echo-test");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(exec, context);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("Command completed successfully");
        assertThat(result.output().toString()).contains("Hello World");
    }

    @Test
    void shouldResolveTemplateInCommand() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("greet", new CommandDefinition("echo 'Hello {name}'"));
        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("greet");
        Map<String, Object> context = Map.of("name", "TestUser");

        ActionResult result = executor.execute(exec, context);

        assertThat(result.success()).isTrue();
        assertThat(result.output().toString()).contains("Hello TestUser");
    }

    @Test
    void shouldReturnFailureOnNonZeroExitCode() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("fail-cmd", new CommandDefinition("exit 1"));
        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("fail-cmd");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(exec, context);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("failed with exit code");
    }

    @Test
    void shouldListAvailableCommandsOnFailure() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("cmd-1", new CommandDefinition("echo 1"));
        registry.registerCommand("cmd-2", new CommandDefinition("echo 2"));
        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("missing");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(exec, context);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("cmd-1");
        assertThat(result.message()).contains("cmd-2");
    }

    @Test
    void shouldCaptureCommandOutput() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand(
                "multi-line",
                new CommandDefinition("echo 'line1' && echo 'line2' && echo 'line3'"));
        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("multi-line");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(exec, context);

        assertThat(result.success()).isTrue();
        assertThat(result.output().toString()).contains("line1");
        assertThat(result.output().toString()).contains("line2");
        assertThat(result.output().toString()).contains("line3");
    }

    // ========== Registry Loading Tests ==========

    @Test
    void shouldCreateEmptyRegistryByDefault() {
        CLIActionExecutor freshExecutor = new CLIActionExecutor();

        Action.Execute exec = new Action.Execute("any-command");
        ActionResult result = freshExecutor.execute(exec, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Command not found");
    }

    @Test
    void shouldAllowSettingCustomRegistry() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("custom-cmd", new CommandDefinition("echo 'custom'"));

        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("custom-cmd");
        ActionResult result = executor.execute(exec, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output().toString()).contains("custom");
    }

    // ========== Context Variable Tests ==========

    @Test
    void shouldHandleEmptyContext() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerCommand("simple", new CommandDefinition("echo 'no vars'"));
        executor.setCommandRegistry(registry);

        Action.Execute exec = new Action.Execute("simple");
        ActionResult result = executor.execute(exec, Map.of());

        assertThat(result.success()).isTrue();
    }

    @Test
    void shouldHandleComplexContextValues() {
        var handler = new PayloadCapturingHandler("complex-handler");
        executor.registerHandler(handler);

        Action.Send send =
                new Action.Send(
                        "complex-handler", Map.of("msg", "Count: {count}, Active: {active}"));
        Map<String, Object> context = Map.of("count", 42, "active", true);

        ActionResult result = executor.execute(send, context);

        assertThat(result.success()).isTrue();
        assertThat(handler.capturedPayload.get("msg")).isEqualTo("Count: 42, Active: true");
    }

    @Test
    void shouldHandleMissingContextVariable() {
        var handler = new PayloadCapturingHandler("missing-var-handler");
        executor.registerHandler(handler);

        Action.Send send =
                new Action.Send("missing-var-handler", Map.of("msg", "Value: {missing}"));
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(send, context);

        assertThat(result.success()).isTrue();
    }

    // Test helpers for action handler tests
    static class TestActionHandler implements ActionHandler {
        private final String handlerId;
        private final boolean success;
        private final String message;

        TestActionHandler(String handlerId, boolean success, String message) {
            this.handlerId = handlerId;
            this.success = success;
            this.message = message;
        }

        @Override
        public String getHandlerId() {
            return handlerId;
        }

        @Override
        public ActionResult execute(Map<String, Object> payload, Map<String, Object> context) {
            return success ? ActionResult.success(message) : ActionResult.failure(message);
        }
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

    static class ContextCapturingHandler implements ActionHandler {
        private final String handlerId;
        Map<String, Object> capturedContext;

        ContextCapturingHandler(String handlerId) {
            this.handlerId = handlerId;
        }

        @Override
        public String getHandlerId() {
            return handlerId;
        }

        @Override
        public ActionResult execute(Map<String, Object> payload, Map<String, Object> context) {
            this.capturedContext = context;
            return ActionResult.success("Captured context");
        }
    }
}
