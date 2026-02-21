package io.hensu.server.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanEvent;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.plan.StepResult;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
            broadcaster.subscribe("exec-1");

            assertThat(broadcaster.hasSubscribers("exec-1")).isTrue();
        }

        @Test
        void shouldReuseExistingProcessorForSameExecutionId() {
            broadcaster.subscribe("exec-1");
            broadcaster.subscribe("exec-1");

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
            // A publish to an unknown execution must silently drop — no exception, no subscriber
            // created
            assertThatCode(
                            () ->
                                    broadcaster.publish(
                                            "exec-1",
                                            ExecutionEvent.ExecutionStarted.now(
                                                    "exec-1", "wf-1", "tenant-1")))
                    .doesNotThrowAnyException();
            assertThat(broadcaster.hasSubscribers("exec-1")).isFalse();
        }

        @Test
        void shouldDeliverMultipleEventsInOrder() {
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
        void shouldCleanupSubscriberEntryAfterComplete() {
            broadcaster.subscribe("exec-1");
            assertThat(broadcaster.hasSubscribers("exec-1")).isTrue();

            broadcaster.complete("exec-1");

            assertThat(broadcaster.hasSubscribers("exec-1")).isFalse();
        }
    }

    @Nested
    class ScopedValueContext {

        @Test
        void runAs_bindsScopedValueForDurationOfCall() throws Exception {
            AtomicBoolean wasBound = new AtomicBoolean(false);
            AtomicReference<String> capturedId = new AtomicReference<>();

            broadcaster.runAs(
                    "exec-1",
                    () -> {
                        wasBound.set(ExecutionEventBroadcaster.CURRENT_EXECUTION.isBound());
                        capturedId.set(ExecutionEventBroadcaster.CURRENT_EXECUTION.get());
                        return null;
                    });

            assertThat(wasBound.get()).isTrue();
            assertThat(capturedId.get()).isEqualTo("exec-1");
        }

        @Test
        void runAs_unbindsContextAfterNormalReturn() throws Exception {
            broadcaster.runAs("exec-1", () -> null);

            // ScopedValue must not be bound outside its frame
            assertThat(ExecutionEventBroadcaster.CURRENT_EXECUTION.isBound()).isFalse();
        }

        @Test
        void runAs_unbindsContextEvenWhenCallableThrows() {
            assertThatThrownBy(
                            () ->
                                    broadcaster.runAs(
                                            "exec-1",
                                            () -> {
                                                throw new RuntimeException("execution failed");
                                            }))
                    .isInstanceOf(RuntimeException.class);

            // The old ThreadLocal set/clear idiom could fail to clear on re-thrown exceptions
            // if the finally block was in a different scope. ScopedValue is structurally
            // scoped so this is guaranteed by the JVM — this test proves we use it correctly.
            assertThat(ExecutionEventBroadcaster.CURRENT_EXECUTION.isBound()).isFalse();
        }

        @Test
        void runAs_nestedCallsBindCorrectIdWithinEachScope() throws Exception {
            AtomicReference<String> outerCapture = new AtomicReference<>();
            AtomicReference<String> innerCapture = new AtomicReference<>();

            broadcaster.runAs(
                    "outer",
                    () -> {
                        outerCapture.set(ExecutionEventBroadcaster.CURRENT_EXECUTION.get());
                        broadcaster.runAs(
                                "inner",
                                () -> {
                                    innerCapture.set(
                                            ExecutionEventBroadcaster.CURRENT_EXECUTION.get());
                                    return null;
                                });
                        return null;
                    });

            assertThat(outerCapture.get()).isEqualTo("outer");
            assertThat(innerCapture.get()).isEqualTo("inner");
        }
    }

    @Nested
    class PlanObserverConversion {

        @Test
        void onEvent_routesViaScopedValueWhenNoPlanMappingExists() throws Exception {
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

            // ScopedValue provides the execution context — no explicit plan registration needed
            // before PlanCreated fires (it registers the plan as a side-effect).
            broadcaster.runAs(
                    "exec-1",
                    () -> {
                        broadcaster.onEvent(PlanEvent.PlanCreated.now(plan));
                        return null;
                    });

            subscriber.awaitItems(1);
            ExecutionEvent event = subscriber.getItems().getFirst();
            assertThat(event.type()).isEqualTo("plan.created");
            ExecutionEvent.PlanCreated created = (ExecutionEvent.PlanCreated) event;
            assertThat(created.steps()).hasSize(2);
        }

        @Test
        void onEvent_prefersPlanMappingOverScopedValue() throws Exception {
            // plan-1 is registered to exec-1
            broadcaster.registerPlan("plan-1", "exec-1");

            AssertSubscriber<ExecutionEvent> subscriberExec1 =
                    broadcaster
                            .subscribe("exec-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));
            AssertSubscriber<ExecutionEvent> subscriberExec2 =
                    broadcaster
                            .subscribe("exec-2")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            // ScopedValue says exec-2, but plan mapping must take priority
            broadcaster.runAs(
                    "exec-2",
                    () -> {
                        PlannedStep step = PlannedStep.simple(0, "search", "desc");
                        broadcaster.onEvent(PlanEvent.StepStarted.now("plan-1", step));
                        return null;
                    });

            subscriberExec1.awaitItems(1);

            // Event was routed to exec-1 (plan mapping), not exec-2 (ScopedValue)
            assertThat(subscriberExec1.getItems()).hasSize(1);
            assertThat(subscriberExec2.getItems()).isEmpty();
        }

        @Test
        void onEvent_convertsStepCompletedWithOutput() {
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
            ExecutionEvent.StepCompleted completed =
                    (ExecutionEvent.StepCompleted) subscriber.getItems().getFirst();
            assertThat(completed.success()).isTrue();
            assertThat(completed.output()).isEqualTo("Result data");
        }

        @Test
        void onEvent_convertsAndRegistersPlanOnPlanCreated() throws Exception {
            AssertSubscriber<ExecutionEvent> subscriber =
                    broadcaster
                            .subscribe("exec-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            Plan plan = Plan.staticPlan("node-1", List.of(PlannedStep.simple(0, "tool", "do it")));

            broadcaster.runAs(
                    "exec-1",
                    () -> {
                        broadcaster.onEvent(PlanEvent.PlanCreated.now(plan));
                        return null;
                    });

            // After PlanCreated, subsequent step events must route via plan mapping
            // (not ScopedValue) — plan ID is now known
            broadcaster.onEvent(PlanEvent.PlanCompleted.success(plan.id(), "Done"));

            subscriber.awaitItems(2);
            assertThat(subscriber.getItems().get(0).type()).isEqualTo("plan.created");
            assertThat(subscriber.getItems().get(1).type()).isEqualTo("plan.completed");
        }
    }
}
