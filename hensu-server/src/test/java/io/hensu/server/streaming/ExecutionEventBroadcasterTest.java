package io.hensu.server.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
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
