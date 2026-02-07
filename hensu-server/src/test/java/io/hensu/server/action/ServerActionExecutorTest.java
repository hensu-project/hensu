package io.hensu.server.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.execution.action.ActionHandler;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ServerActionExecutorTest {

    private ServerActionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ServerActionExecutor();
    }

    @Nested
    class ExecuteSend {

        @Test
        void shouldDelegateToRegisteredHandler() {
            ActionHandler handler = mock(ActionHandler.class);
            when(handler.getHandlerId()).thenReturn("slack");
            when(handler.execute(any(), any())).thenReturn(ActionResult.success("sent"));

            executor.registerHandler(handler);

            Action.Send send = new Action.Send("slack", Map.of("message", "hello"));
            ActionResult result = executor.execute(send, Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.message()).isEqualTo("sent");
            verify(handler).execute(eq(Map.of("message", "hello")), eq(Map.of()));
        }

        @Test
        void shouldReturnFailureWhenHandlerNotFound() {
            Action.Send send = new Action.Send("unknown");
            ActionResult result = executor.execute(send, Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("Action handler not found: unknown");
        }

        @Test
        void shouldResolveTemplateVariablesInPayload() {
            ActionHandler handler = mock(ActionHandler.class);
            when(handler.getHandlerId()).thenReturn("notify");
            when(handler.execute(any(), any())).thenReturn(ActionResult.success("ok"));

            executor.registerHandler(handler);

            Action.Send send = new Action.Send("notify", Map.of("msg", "{greeting}"));
            executor.execute(send, Map.of("greeting", "hello world"));

            verify(handler).execute(eq(Map.of("msg", "hello world")), any());
        }

        @Test
        void shouldPassNonStringValuesThrough() {
            ActionHandler handler = mock(ActionHandler.class);
            when(handler.getHandlerId()).thenReturn("notify");
            when(handler.execute(any(), any())).thenReturn(ActionResult.success("ok"));

            executor.registerHandler(handler);

            Action.Send send = new Action.Send("notify", Map.of("count", 42));
            executor.execute(send, Map.of());

            verify(handler).execute(eq(Map.of("count", 42)), any());
        }
    }

    @Nested
    class ExecuteAction {

        @Test
        void shouldRejectLocalCommandExecution() {
            Action.Execute exec = new Action.Execute("bash-cmd");
            ActionResult result = executor.execute(exec, Map.of());

            assertThat(result.success()).isFalse();
            assertThat(result.message())
                    .contains("Server mode does not support local command execution");
        }
    }

    @Nested
    class HandlerRegistration {

        @Test
        void shouldRegisterAndRetrieveHandler() {
            ActionHandler handler = mock(ActionHandler.class);
            when(handler.getHandlerId()).thenReturn("mcp");

            executor.registerHandler(handler);

            assertThat(executor.getHandler("mcp")).isPresent().contains(handler);
        }

        @Test
        void shouldReturnEmptyForUnknownHandler() {
            assertThat(executor.getHandler("nonexistent")).isEmpty();
        }
    }
}
