package io.hensu.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.WorkflowStateReader;
import io.hensu.server.security.RequestTenantResolver;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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
        WorkflowStateReader states = mock(WorkflowStateReader.class);
        when(states.findByExecutionId(any(), any())).thenReturn(Optional.empty());
        when(states.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(
                        Optional.of(
                                new HensuSnapshot(
                                        "wf-1",
                                        "exec-1",
                                        "node-1",
                                        Map.of(),
                                        Map.of(),
                                        null,
                                        null,
                                        Instant.now(),
                                        "checkpoint",
                                        0)));
        resource = new ExecutionEventResource(broadcaster, tenantResolver, states);
    }

    @Nested
    class StreamEvents {

        @Test
        void shouldAnswerAnotherTenantsExecutionAsNotFound() {
            assertThatThrownBy(() -> resource.streamEvents("exec-of-someone-else"))
                    .isInstanceOf(NotFoundException.class);
        }

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
