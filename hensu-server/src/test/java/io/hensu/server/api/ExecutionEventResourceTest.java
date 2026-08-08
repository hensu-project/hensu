package io.hensu.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.server.security.RequestTenantResolver;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExecutionEventResourceTest {

    private ExecutionEventBroadcaster broadcaster;
    private ExecutionEventResource resource;

    @BeforeEach
    void setUp() {
        broadcaster = mock(ExecutionEventBroadcaster.class);
        RequestTenantResolver tenantResolver = mock(RequestTenantResolver.class);
        when(tenantResolver.tenantId()).thenReturn("tenant-1");
        resource = new ExecutionEventResource(broadcaster, tenantResolver);
    }

    @Nested
    class StreamEvents {

        @Test
        void shouldStreamEventsFromBroadcaster() {
            ExecutionEvent event1 =
                    ExecutionEvent.ExecutionStarted.now("exec-1", "wf-1", "tenant-1");
            ExecutionEvent event2 =
                    ExecutionEvent.ExecutionPaused.now(
                            "exec-1", "wf-1", "node-1", "corr-1", "review", Map.of());
            ExecutionEvent event3 =
                    ExecutionEvent.ExecutionCompleted.success("exec-1", "wf-1", "end", Map.of());

            Multi<ExecutionEvent> mockStream = Multi.createFrom().items(event1, event2, event3);
            when(broadcaster.subscribe("exec-1")).thenReturn(mockStream);

            Multi<ExecutionEvent> result = resource.streamEvents("exec-1");

            AssertSubscriber<ExecutionEvent> subscriber =
                    result.subscribe().withSubscriber(AssertSubscriber.create(10));
            subscriber.awaitCompletion();

            assertThat(subscriber.getItems()).hasSize(3);
            assertThat(subscriber.getItems().get(0).type()).isEqualTo("execution.started");
            assertThat(subscriber.getItems().get(1).type()).isEqualTo("execution.paused");
            assertThat(subscriber.getItems().get(2).type()).isEqualTo("execution.completed");
        }

        @Test
        void shouldStreamCompletionEventWithOutput() {
            Map<String, Object> workflowOutput = Map.of("summary", "Order validated", "count", 3);
            ExecutionEvent completedEvent =
                    ExecutionEvent.ExecutionCompleted.success(
                            "exec-1", "wf-1", "end-node", workflowOutput);

            Multi<ExecutionEvent> mockStream = Multi.createFrom().item(completedEvent);
            when(broadcaster.subscribe("exec-1")).thenReturn(mockStream);

            Multi<ExecutionEvent> result = resource.streamEvents("exec-1");

            AssertSubscriber<ExecutionEvent> subscriber =
                    result.subscribe().withSubscriber(AssertSubscriber.create(5));
            subscriber.awaitCompletion();

            assertThat(subscriber.getItems()).hasSize(1);
            ExecutionEvent.ExecutionCompleted received =
                    (ExecutionEvent.ExecutionCompleted) subscriber.getItems().getFirst();
            assertThat(received.type()).isEqualTo("execution.completed");
            assertThat(received.success()).isTrue();
            assertThat(received.output()).containsEntry("summary", "Order validated");
            assertThat(received.output()).containsEntry("count", 3);
        }
    }

    @Nested
    class StreamAllEvents {

        @Test
        void shouldStreamAllTenantEvents() {
            ExecutionEvent event1 =
                    ExecutionEvent.ExecutionStarted.now("exec-1", "wf-1", "tenant-1");
            ExecutionEvent event2 =
                    ExecutionEvent.ExecutionStarted.now("exec-2", "wf-2", "tenant-1");

            Multi<ExecutionEvent> mockStream = Multi.createFrom().items(event1, event2);
            when(broadcaster.subscribe("tenant:tenant-1")).thenReturn(mockStream);

            Multi<ExecutionEvent> result = resource.streamAllEvents();

            AssertSubscriber<ExecutionEvent> subscriber =
                    result.subscribe().withSubscriber(AssertSubscriber.create(10));
            subscriber.awaitCompletion();

            assertThat(subscriber.getItems()).hasSize(2);
            assertThat(subscriber.getItems().get(0).executionId()).isEqualTo("exec-1");
            assertThat(subscriber.getItems().get(1).executionId()).isEqualTo("exec-2");
        }
    }
}
