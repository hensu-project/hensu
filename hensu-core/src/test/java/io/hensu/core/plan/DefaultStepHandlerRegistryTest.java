package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DefaultStepHandlerRegistryTest {

    private DefaultStepHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultStepHandlerRegistry();
    }

    @Nested
    class Dispatch {

        @Test
        void shouldReturnFailureForUnregisteredActionType() {
            // Synthesize step with no handler registered — registry must not throw
            PlannedStep step = PlannedStep.synthesize(0, null, "Summarise output");

            StepResult result = registry.dispatch(step, Map.of());

            assertThat(result.isFailure()).isTrue();
            assertThat(result.stepIndex()).isZero();
            assertThat(result.error()).contains("No handler registered for action type");
        }

        @Test
        void shouldDispatchToRegisteredHandler() {
            StepResult expected = StepResult.success(0, "tool", "output", Duration.ZERO);
            StepHandler<PlanStepAction.ToolCall> handler = mock(StepHandler.class);
            when(handler.getActionType()).thenReturn(PlanStepAction.ToolCall.class);
            when(handler.handle(any(), any(), any())).thenReturn(expected);

            registry.register(handler);

            PlannedStep step = PlannedStep.simple(0, "tool", "desc");
            StepResult result = registry.dispatch(step, Map.of());

            verify(handler).handle(any(), any(), any());
            assertThat(result).isSameAs(expected);
        }

        @Test
        void shouldUseLastRegisteredHandlerWhenSameTypeRegisteredTwice() {
            StepHandler<PlanStepAction.ToolCall> first = mock(StepHandler.class);
            when(first.getActionType()).thenReturn(PlanStepAction.ToolCall.class);
            when(first.handle(any(), any(), any()))
                    .thenReturn(StepResult.success(0, "tool", "from-first", Duration.ZERO));

            StepHandler<PlanStepAction.ToolCall> second = mock(StepHandler.class);
            when(second.getActionType()).thenReturn(PlanStepAction.ToolCall.class);
            when(second.handle(any(), any(), any()))
                    .thenReturn(StepResult.success(0, "tool", "from-second", Duration.ZERO));

            registry.register(first);
            registry.register(second);

            PlannedStep step = PlannedStep.simple(0, "tool", "desc");
            StepResult result = registry.dispatch(step, Map.of());

            assertThat(result.output()).isEqualTo("from-second");
        }
    }
}
