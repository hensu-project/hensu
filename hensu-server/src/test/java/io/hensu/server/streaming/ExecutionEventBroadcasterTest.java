package io.hensu.server.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanEvent;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.plan.StepResult;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExecutionEventBroadcasterTest {

    private ExecutionEventBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new ExecutionEventBroadcaster();
    }

    @Nested
    class Subscribe {

        @Test
        void shouldCreateSubscriptionForExecution() {
            Multi<ExecutionEvent> events = broadcaster.subscribe("exec-1");

            assertThat(events).isNotNull();
            assertThat(broadcaster.hasSubscribers("exec-1")).isTrue();
        }

        @Test
        void shouldReturnSameStreamForMultipleSubscriptions() {
            Multi<ExecutionEvent> first = broadcaster.subscribe("exec-1");
            Multi<ExecutionEvent> second = broadcaster.subscribe("exec-1");

            assertThat(broadcaster.activeSubscriptionCount()).isEqualTo(1);
        }
    }

    @Nested
    class Publish {

        @Test
        void shouldDeliverEventsToSubscribers() {
            AssertSubscriber<ExecutionEvent> subscriber =
                    broadcaster
                            .subscribe("exec-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            ExecutionEvent event =
                    ExecutionEvent.ExecutionStarted.now("exec-1", "wf-1", "tenant-1");
            broadcaster.publish("exec-1", event);

            subscriber.awaitItems(1);
            assertThat(subscriber.getItems()).hasSize(1);
            assertThat(subscriber.getItems().getFirst().type()).isEqualTo("execution.started");
        }

        @Test
        void shouldNotFailWhenNoSubscribers() {
            ExecutionEvent event =
                    ExecutionEvent.ExecutionStarted.now("exec-1", "wf-1", "tenant-1");

            // Should not throw
            broadcaster.publish("exec-1", event);
        }

        @Test
        void shouldDeliverMultipleEvents() {
            AssertSubscriber<ExecutionEvent> subscriber =
                    broadcaster
                            .subscribe("exec-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            broadcaster.publish(
                    "exec-1", ExecutionEvent.ExecutionStarted.now("exec-1", "wf-1", "tenant-1"));
            broadcaster.publish(
                    "exec-1",
                    new ExecutionEvent.StepStarted(
                            "exec-1", "plan-1", 0, "tool", "desc", java.time.Instant.now()));
            broadcaster.publish(
                    "exec-1",
                    new ExecutionEvent.StepCompleted(
                            "exec-1", "plan-1", 0, true, "output", null, java.time.Instant.now()));

            subscriber.awaitItems(3);
            assertThat(subscriber.getItems()).hasSize(3);
            assertThat(subscriber.getItems().get(0).type()).isEqualTo("execution.started");
            assertThat(subscriber.getItems().get(1).type()).isEqualTo("step.started");
            assertThat(subscriber.getItems().get(2).type()).isEqualTo("step.completed");
        }
    }

    @Nested
    class Complete {

        @Test
        void shouldCompleteStreamWhenExecutionEnds() {
            AssertSubscriber<ExecutionEvent> subscriber =
                    broadcaster
                            .subscribe("exec-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            broadcaster.publish(
                    "exec-1", ExecutionEvent.ExecutionStarted.now("exec-1", "wf-1", "tenant-1"));
            broadcaster.complete("exec-1");

            subscriber.awaitCompletion(Duration.ofSeconds(1));
            assertThat(subscriber.getItems()).hasSize(1);
        }

        @Test
        void shouldCleanupResources() {
            broadcaster.subscribe("exec-1");
            assertThat(broadcaster.hasSubscribers("exec-1")).isTrue();

            broadcaster.complete("exec-1");
            assertThat(broadcaster.hasSubscribers("exec-1")).isFalse();
        }
    }

    @Nested
    class PlanObserver {

        @Test
        void shouldConvertPlanCreatedEvent() {
            broadcaster.setCurrentExecution("exec-1");

            AssertSubscriber<ExecutionEvent> subscriber =
                    broadcaster
                            .subscribe("exec-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            Plan plan =
                    Plan.staticPlan(
                            "node-1",
                            List.of(
                                    PlannedStep.simple(0, "tool-1", "Step 1"),
                                    PlannedStep.simple(1, "tool-2", "Step 2")));

            broadcaster.onEvent(PlanEvent.PlanCreated.now(plan));

            subscriber.awaitItems(1);
            ExecutionEvent event = subscriber.getItems().getFirst();
            assertThat(event.type()).isEqualTo("plan.created");
            assertThat(event).isInstanceOf(ExecutionEvent.PlanCreated.class);

            ExecutionEvent.PlanCreated created = (ExecutionEvent.PlanCreated) event;
            assertThat(created.steps()).hasSize(2);
        }

        @Test
        void shouldConvertStepStartedEvent() {
            broadcaster.setCurrentExecution("exec-1");
            broadcaster.registerPlan("plan-1", "exec-1");

            AssertSubscriber<ExecutionEvent> subscriber =
                    broadcaster
                            .subscribe("exec-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            PlannedStep step = PlannedStep.simple(0, "search", "Search for data");
            broadcaster.onEvent(PlanEvent.StepStarted.now("plan-1", step));

            subscriber.awaitItems(1);
            ExecutionEvent event = subscriber.getItems().getFirst();
            assertThat(event.type()).isEqualTo("step.started");

            ExecutionEvent.StepStarted started = (ExecutionEvent.StepStarted) event;
            assertThat(started.toolName()).isEqualTo("search");
            assertThat(started.stepIndex()).isEqualTo(0);
        }

        @Test
        void shouldConvertStepCompletedEvent() {
            broadcaster.setCurrentExecution("exec-1");
            broadcaster.registerPlan("plan-1", "exec-1");

            AssertSubscriber<ExecutionEvent> subscriber =
                    broadcaster
                            .subscribe("exec-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            StepResult result =
                    StepResult.success(0, "tool-1", "Result data", Duration.ofMillis(100));
            broadcaster.onEvent(PlanEvent.StepCompleted.now("plan-1", result));

            subscriber.awaitItems(1);
            ExecutionEvent event = subscriber.getItems().getFirst();
            assertThat(event.type()).isEqualTo("step.completed");

            ExecutionEvent.StepCompleted completed = (ExecutionEvent.StepCompleted) event;
            assertThat(completed.success()).isTrue();
            assertThat(completed.output()).isEqualTo("Result data");
        }

        @Test
        void shouldConvertPlanCompletedEvent() {
            broadcaster.setCurrentExecution("exec-1");
            broadcaster.registerPlan("plan-1", "exec-1");

            AssertSubscriber<ExecutionEvent> subscriber =
                    broadcaster
                            .subscribe("exec-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            broadcaster.onEvent(PlanEvent.PlanCompleted.success("plan-1", "Final output"));

            subscriber.awaitItems(1);
            ExecutionEvent event = subscriber.getItems().getFirst();
            assertThat(event.type()).isEqualTo("plan.completed");

            ExecutionEvent.PlanCompleted completed = (ExecutionEvent.PlanCompleted) event;
            assertThat(completed.success()).isTrue();
            assertThat(completed.output()).isEqualTo("Final output");
        }

        @Test
        void shouldRouteEventsByPlanId() {
            // Register plan with execution
            broadcaster.registerPlan("plan-1", "exec-1");

            AssertSubscriber<ExecutionEvent> subscriber =
                    broadcaster
                            .subscribe("exec-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            // No current execution set, but plan is registered
            broadcaster.onEvent(PlanEvent.PlanCompleted.success("plan-1", "Done"));

            subscriber.awaitItems(1);
            assertThat(subscriber.getItems().getFirst().executionId()).isEqualTo("exec-1");
        }
    }

    @Nested
    class ThreadLocalContext {

        @Test
        void shouldSetAndClearCurrentExecution() {
            assertThat(broadcaster.getCurrentExecution()).isNull();

            broadcaster.setCurrentExecution("exec-1");
            assertThat(broadcaster.getCurrentExecution()).isEqualTo("exec-1");

            broadcaster.setCurrentExecution(null);
            assertThat(broadcaster.getCurrentExecution()).isNull();
        }
    }
}
