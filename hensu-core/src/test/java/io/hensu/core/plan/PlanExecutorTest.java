package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.plan.PlanEvent.PlanCompleted;
import io.hensu.core.plan.PlanEvent.PlanCreated;
import io.hensu.core.plan.PlanEvent.StepCompleted;
import io.hensu.core.plan.PlanEvent.StepStarted;
import io.hensu.core.plan.PlanResult.PlanStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlanExecutorTest {

    private ActionExecutor mockActionExecutor;
    private PlanExecutor executor;
    private List<PlanEvent> capturedEvents;

    @BeforeEach
    void setUp() {
        mockActionExecutor = mock(ActionExecutor.class);
        executor = new PlanExecutor(mockActionExecutor);
        capturedEvents = new ArrayList<>();
        PlanObserver captureObserver = capturedEvents::add;
        executor.addObserver(captureObserver);
    }

    @Nested
    class Construction {

        @Test
        void shouldThrowWhenActionExecutorIsNull() {
            assertThatThrownBy(() -> new PlanExecutor(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("actionExecutor");
        }
    }

    @Nested
    class Observers {

        @Test
        void shouldAddObserver() {
            List<PlanEvent> events = new ArrayList<>();
            PlanObserver observer = events::add;

            executor.addObserver(observer);
            executor.execute(Plan.staticPlan("node", List.of()), Map.of());

            assertThat(events).isNotEmpty();
        }

        @Test
        void shouldRemoveObserver() {
            List<PlanEvent> events = new ArrayList<>();
            PlanObserver observer = events::add;

            executor.addObserver(observer);
            boolean removed = executor.removeObserver(observer);
            executor.execute(Plan.staticPlan("node", List.of()), Map.of());

            assertThat(removed).isTrue();
            assertThat(events).isEmpty();
        }

        @Test
        void shouldNotFailOnObserverException() {
            PlanObserver failingObserver =
                    _ -> {
                        throw new RuntimeException("Observer failed");
                    };

            executor.addObserver(failingObserver);

            Plan plan = Plan.staticPlan("node", List.of());
            PlanResult result = executor.execute(plan, Map.of());

            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    class EmptyPlan {

        @Test
        void shouldSucceedWithEmptyPlan() {
            Plan plan = Plan.staticPlan("node", List.of());

            PlanResult result = executor.execute(plan, Map.of());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.stepResults()).isEmpty();
        }

        @Test
        void shouldEmitEventsForEmptyPlan() {
            Plan plan = Plan.staticPlan("node", List.of());

            executor.execute(plan, Map.of());

            assertThat(capturedEvents).hasSize(2);
            assertThat(capturedEvents.get(0)).isInstanceOf(PlanCreated.class);
            assertThat(capturedEvents.get(1)).isInstanceOf(PlanCompleted.class);
        }
    }

    @Nested
    class SuccessfulExecution {

        @Test
        void shouldExecuteAllSteps() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.success("Done", "output"));

            Plan plan =
                    Plan.staticPlan(
                            "node",
                            List.of(
                                    PlannedStep.simple(0, "tool1", "Step 1"),
                                    PlannedStep.simple(1, "tool2", "Step 2")));

            PlanResult result = executor.execute(plan, Map.of());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.stepResults()).hasSize(2);
            assertThat(result.completedStepCount()).isEqualTo(2);
        }

        @Test
        void shouldEmitEventsForEachStep() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.success("Done"));

            Plan plan = Plan.staticPlan("node", List.of(PlannedStep.simple(0, "tool1", "Step 1")));

            executor.execute(plan, Map.of());

            // PlanCreated, StepStarted, StepCompleted, PlanCompleted
            assertThat(capturedEvents).hasSize(4);
            assertThat(capturedEvents.get(0)).isInstanceOf(PlanCreated.class);
            assertThat(capturedEvents.get(1)).isInstanceOf(StepStarted.class);
            assertThat(capturedEvents.get(2)).isInstanceOf(StepCompleted.class);
            assertThat(capturedEvents.get(3)).isInstanceOf(PlanCompleted.class);

            PlanCompleted completed = (PlanCompleted) capturedEvents.get(3);
            assertThat(completed.success()).isTrue();
        }

        @Test
        void shouldPassLastOutputToResult() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.success("Done", "final output"));

            Plan plan = Plan.staticPlan("node", List.of(PlannedStep.simple(0, "tool", "Step")));

            PlanResult result = executor.execute(plan, Map.of());

            assertThat(result.output()).isEqualTo("final output");
        }
    }

    @Nested
    class FailedExecution {

        @Test
        void shouldStopOnFirstFailure() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.success("Done"))
                    .thenReturn(ActionResult.failure("Failed"))
                    .thenReturn(ActionResult.success("Should not run"));

            Plan plan =
                    Plan.staticPlan(
                            "node",
                            List.of(
                                    PlannedStep.simple(0, "tool1", "Step 1"),
                                    PlannedStep.simple(1, "tool2", "Step 2"),
                                    PlannedStep.simple(2, "tool3", "Step 3")));

            PlanResult result = executor.execute(plan, Map.of());

            assertThat(result.isFailure()).isTrue();
            assertThat(result.status()).isEqualTo(PlanStatus.FAILED);
            assertThat(result.failedAtStep()).isEqualTo(1);
            assertThat(result.stepResults()).hasSize(2);
        }

        @Test
        void shouldCaptureErrorMessage() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.failure("Connection timeout"));

            Plan plan = Plan.staticPlan("node", List.of(PlannedStep.simple(0, "api", "Call API")));

            PlanResult result = executor.execute(plan, Map.of());

            assertThat(result.error()).isEqualTo("Connection timeout");
        }

        @Test
        void shouldEmitFailureEvents() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.failure("Error"));

            Plan plan = Plan.staticPlan("node", List.of(PlannedStep.simple(0, "tool", "Step")));

            executor.execute(plan, Map.of());

            PlanCompleted completed =
                    capturedEvents.stream()
                            .filter(e -> e instanceof PlanCompleted)
                            .map(e -> (PlanCompleted) e)
                            .findFirst()
                            .orElseThrow();

            assertThat(completed.success()).isFalse();
        }
    }

    @Nested
    class ExceptionHandling {

        @Test
        void shouldHandleActionExecutorException() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenThrow(new RuntimeException("Unexpected error"));

            Plan plan = Plan.staticPlan("node", List.of(PlannedStep.simple(0, "tool", "Step")));

            PlanResult result = executor.execute(plan, Map.of());

            assertThat(result.isFailure()).isTrue();
            assertThat(result.error()).contains("Unexpected error");
        }
    }

    @Nested
    class Timeout {

        @Test
        void shouldTimeoutIfDurationExceeded() {
            // Duration.ZERO: any elapsed time between plan creation and the timeout
            // check exceeds the constraint. No sleep required — JVM execution takes > 0ns.
            Plan plan =
                    Plan.staticPlan("node", List.of(PlannedStep.simple(0, "tool", "Step")))
                            .withConstraints(
                                    PlanConstraints.defaults().withMaxDuration(Duration.ZERO));

            PlanResult result = executor.execute(plan, Map.of());

            assertThat(result.status()).isEqualTo(PlanStatus.TIMEOUT);
        }
    }

    @Nested
    class ContextPropagation {

        @Test
        void shouldPassContextToActionExecutor() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenAnswer(
                            invocation -> {
                                Map<String, Object> ctx = invocation.getArgument(1);
                                if (ctx.containsKey("orderId")) {
                                    return ActionResult.success("Got order: " + ctx.get("orderId"));
                                }
                                return ActionResult.failure("Missing orderId");
                            });

            Plan plan =
                    Plan.staticPlan("node", List.of(PlannedStep.simple(0, "get_order", "Fetch")));

            PlanResult result = executor.execute(plan, Map.of("orderId", "123"));

            assertThat(result.isSuccess()).isTrue();
        }
    }
}
