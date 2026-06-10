package io.hensu.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkflowExecutionServiceTest {

    private WorkflowExecutor workflowExecutor;
    private WorkflowStateRepository stateRepository;
    private ExecutionEventBroadcaster eventBroadcaster;
    private WorkflowRegistryService registryService;
    private WorkflowExecutionService service;

    private final CountDownLatch executionComplete = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        workflowExecutor = mock(WorkflowExecutor.class);
        stateRepository = mock(WorkflowStateRepository.class);
        eventBroadcaster = mock(ExecutionEventBroadcaster.class);
        registryService = mock(WorkflowRegistryService.class);

        // Execute runAs inline so the virtual thread body runs synchronously within it
        try {
            doAnswer(invocation -> ((Callable<?>) invocation.getArgument(1)).call())
                    .when(eventBroadcaster)
                    .runAs(any(), any());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Signal when the SSE stream is closed (last action in the finally block)
        doAnswer(
                        _ -> {
                            executionComplete.countDown();
                            return null;
                        })
                .when(eventBroadcaster)
                .complete(any());

        service =
                new WorkflowExecutionService(
                        workflowExecutor, stateRepository, eventBroadcaster, registryService);
    }

    @Test
    void shouldPersistFailedSnapshotWhenExceptionOccursBeforeFirstCheckpoint() throws Exception {
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.execute(any(), any(), any()))
                .thenThrow(new RuntimeException("agent provider unavailable"));

        service.startExecution("tenant-1", "wf-1", Map.of());

        // Wait for virtual thread to complete (signalled by eventBroadcaster.complete)
        assertThat(executionComplete.await(5, TimeUnit.SECONDS))
                .as("Virtual thread should complete within timeout")
                .isTrue();

        ArgumentCaptor<HensuSnapshot> savedCaptor = ArgumentCaptor.forClass(HensuSnapshot.class);
        verify(stateRepository).save(eq("tenant-1"), savedCaptor.capture());
        assertThat(savedCaptor.getValue().checkpointReason()).isEqualTo("failed");
        assertThat(savedCaptor.getValue().workflowId()).isEqualTo("wf-1");

        verify(eventBroadcaster).publish(any(), any(ExecutionEvent.ExecutionError.class));
    }
}
