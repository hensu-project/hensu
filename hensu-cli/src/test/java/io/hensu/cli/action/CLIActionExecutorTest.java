package io.hensu.cli.action;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor.ActionResult;
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

    // ========== Notify Action Tests ==========

    @Test
    void shouldExecuteNotifyActionSuccessfully() {
        Action.Notify notify = new Action.Notify("Test notification message");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(notify, context);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("Notification sent");
        assertThat(result.message()).contains("Test notification message");
    }

    @Test
    void shouldResolveTemplateInNotifyMessage() {
        Action.Notify notify = new Action.Notify("Hello {name}, your status is {status}");
        Map<String, Object> context = Map.of("name", "User", "status", "active");

        ActionResult result = executor.execute(notify, context);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("Hello User, your status is active");
    }

    @Test
    void shouldHandleNotifyWithChannel() {
        Action.Notify notify = new Action.Notify("Alert message", "alerts");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(notify, context);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("Alert message");
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

    // ========== HttpCall Action Tests ==========

    @Test
    void shouldFailHttpCallWithInvalidEndpoint() {
        Action.HttpCall http = new Action.HttpCall("invalid-url", "test-command");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(http, context);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("HTTP call failed");
    }

    @Test
    void shouldResolveTemplateInHttpEndpoint() {
        Action.HttpCall http =
                new Action.HttpCall(
                        "https://{host}/api/endpoint", "POST", "cmd-id", Map.of(), null, 1000);
        Map<String, Object> context = Map.of("host", "example.com");

        ActionResult result = executor.execute(http, context);

        assertThat(result.success()).isFalse();
    }

    @Test
    void shouldResolveTemplateInHttpBody() {
        Action.HttpCall http =
                new Action.HttpCall(
                        "https://example.com/api",
                        "POST",
                        "cmd-id",
                        Map.of(),
                        "{\"data\": \"{value}\"}",
                        1000);
        Map<String, Object> context = Map.of("value", "test-value");

        ActionResult result = executor.execute(http, context);

        assertThat(result.success()).isFalse();
    }

    @Test
    void shouldUseDefaultBodyWhenNotProvided() {
        Action.HttpCall http = new Action.HttpCall("https://example.com/api", "my-command");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(http, context);

        assertThat(result.success()).isFalse();
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
        Action.Notify notify = new Action.Notify("Count: {count}, Active: {active}");
        Map<String, Object> context = Map.of("count", 42, "active", true);

        ActionResult result = executor.execute(notify, context);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("Count: 42");
        assertThat(result.message()).contains("Active: true");
    }

    @Test
    void shouldHandleMissingContextVariable() {
        Action.Notify notify = new Action.Notify("Value: {missing}");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(notify, context);

        assertThat(result.success()).isTrue();
    }

    // ========== Edge Cases ==========

    @Test
    void shouldHandleEmptyNotifyMessage() {
        Action.Notify notify = new Action.Notify("");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(notify, context);

        assertThat(result.success()).isTrue();
    }

    @Test
    void shouldHandleSpecialCharactersInMessage() {
        Action.Notify notify = new Action.Notify("Special chars: $!@#%^&*()");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(notify, context);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("$!@#%^&*()");
    }

    @Test
    void shouldHandleUnicodeInMessage() {
        Action.Notify notify = new Action.Notify("Unicode: ✓ ✗ →");
        Map<String, Object> context = Map.of();

        ActionResult result = executor.execute(notify, context);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("✓");
    }
}
