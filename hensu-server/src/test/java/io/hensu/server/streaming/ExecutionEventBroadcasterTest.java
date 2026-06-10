package io.hensu.server.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanEvent;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.plan.StepResult;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    class EventDelivery {

        @Test
        void shouldDeliverEventAndSignalCompletionToSubscriber() {
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
            assertThat(subscriber.getItems().getFirst().type()).isEqualTo("execution.started");
            // After complete, processor must be cleaned up
            assertThat(broadcaster.hasSubscribers("exec-1")).isFalse();
        }

        @Test
        void shouldSilentlyDropEventsWhenNoSubscribersExist() {
            // Publish to unknown execution — must not create a subscriber or throw
            broadcaster.publish(
                    "exec-1", ExecutionEvent.ExecutionStarted.now("exec-1", "wf-1", "tenant-1"));
            assertThat(broadcaster.hasSubscribers("exec-1")).isFalse();
        }
    }

    @Nested
    class PlanObserverRouting {

        @Test
        void shouldRoutePlanEventViaScopedValueWhenNoPlanMappingExists() throws Exception {
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

            broadcaster.runAs(
                    "exec-1",
                    () -> {
                        broadcaster.onEvent(PlanEvent.PlanCreated.now(plan));
                        return null;
                    });

            subscriber.awaitItems(1);
            ExecutionEvent.PlanCreated created =
                    (ExecutionEvent.PlanCreated) subscriber.getItems().getFirst();
            assertThat(created.steps()).hasSize(2);
        }

        @Test
        void shouldPreferPlanMappingOverScopedValue() throws Exception {
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

            // ScopedValue says exec-2, but plan mapping must win
            broadcaster.runAs(
                    "exec-2",
                    () -> {
                        PlannedStep step = PlannedStep.simple(0, "search", "desc");
                        broadcaster.onEvent(PlanEvent.StepStarted.now("plan-1", step));
                        return null;
                    });

            subscriberExec1.awaitItems(1);
            assertThat(subscriberExec1.getItems()).hasSize(1);
            assertThat(subscriberExec2.getItems()).isEmpty();
        }

        @Test
        void shouldRegisterPlanOnPlanCreatedThenRouteLaterEventsByPlanId() throws Exception {
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

            // After PlanCreated registered the mapping, events outside ScopedValue
            // must still route correctly via plan ID
            broadcaster.onEvent(PlanEvent.PlanCompleted.success(plan.id(), "Done"));

            subscriber.awaitItems(2);
            assertThat(subscriber.getItems().get(0).type()).isEqualTo("plan.created");
            assertThat(subscriber.getItems().get(1).type()).isEqualTo("plan.completed");
        }

        @Test
        void shouldDropEventsForUnknownPlanIdOutsideScopedValue() {
            // No plan mapping, no ScopedValue → event must be silently dropped
            AssertSubscriber<ExecutionEvent> subscriber =
                    broadcaster
                            .subscribe("exec-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            StepResult result = StepResult.success(0, "tool-1", "output", Duration.ofMillis(50));
            broadcaster.onEvent(PlanEvent.StepCompleted.now("unknown-plan", result));

            // Allow time for any async delivery, then verify nothing arrived
            assertThat(subscriber.getItems()).isEmpty();
        }
    }

    @Nested
    class ConcurrentAccess {

        @Test
        void shouldHandleConcurrentPublishFromMultipleThreads() throws Exception {
            AssertSubscriber<ExecutionEvent> subscriber =
                    broadcaster
                            .subscribe("exec-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(100));

            int threadCount = 10;
            int eventsPerThread = 5;
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicReference<Throwable> error = new AtomicReference<>();

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int t = 0; t < threadCount; t++) {
                    pool.submit(
                            () -> {
                                try {
                                    barrier.await(2, TimeUnit.SECONDS);
                                    for (int i = 0; i < eventsPerThread; i++) {
                                        broadcaster.publish(
                                                "exec-1",
                                                ExecutionEvent.ExecutionStarted.now(
                                                        "exec-1", "wf-1", "tenant-1"));
                                    }
                                } catch (Throwable ex) {
                                    error.compareAndSet(null, ex);
                                } finally {
                                    done.countDown();
                                }
                            });
                }

                done.await(5, TimeUnit.SECONDS);
            }

            assertThat(error.get()).isNull();
            subscriber.awaitItems(threadCount * eventsPerThread);
            assertThat(subscriber.getItems()).hasSize(threadCount * eventsPerThread);
        }
    }
}
