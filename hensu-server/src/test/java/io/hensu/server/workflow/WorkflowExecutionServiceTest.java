package io.hensu.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.persistence.ExecutionLeaseManager;
import io.hensu.server.persistence.InMemoryToolAuditRepository;
import io.hensu.server.persistence.LeaseLostException;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class WorkflowExecutionServiceTest {

    private WorkflowExecutor workflowExecutor;
    private WorkflowStateRepository stateRepository;
    private ExecutionEventBroadcaster eventBroadcaster;
    private WorkflowRegistryService registryService;
    private ExecutionLeaseManager leaseManager;
    private WorkflowExecutionService service;

    private final CountDownLatch executionComplete = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        workflowExecutor = mock(WorkflowExecutor.class);
        stateRepository = mock(WorkflowStateRepository.class);
        eventBroadcaster = mock(ExecutionEventBroadcaster.class);
        registryService = mock(WorkflowRegistryService.class);
        leaseManager = mock(ExecutionLeaseManager.class);
        when(leaseManager.getServerNodeId()).thenReturn("node-a");

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
                        workflowExecutor,
                        stateRepository,
                        eventBroadcaster,
                        registryService,
                        new InMemoryToolAuditRepository(),
                        leaseManager);
    }

    /// Another node claimed the execution while this one stalled. Writing a failed
    /// snapshot would be refused, and would be false: the execution is still alive
    /// on the node that took it over.
    @Test
    void shouldStopWithoutRecordingFailureWhenLeaseIsLost() throws Exception {
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.execute(any(), any(), any()))
                .thenThrow(new LeaseLostException("exec-1"));

        service.startExecution("tenant-1", "wf-1", Map.of());

        assertThat(executionComplete.await(5, TimeUnit.SECONDS)).isTrue();
        verify(stateRepository, never()).save(any(), any());
        verify(eventBroadcaster, never()).publish(any(), any(ExecutionEvent.ExecutionError.class));
    }

    /// Started here, the execution would take its lease on this node at its first
    /// checkpoint and fail at its first tool call, because the tenant's relay is
    /// connected to another node. It is refused before anything is dispatched.
    @Test
    void shouldRefuseStartWhenTenantSessionIsOnAnotherNode() {
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(leaseManager.sessionElsewhere("tenant-1")).thenReturn(Optional.of("node-b"));

        assertThatThrownBy(() -> service.startExecution("tenant-1", "wf-1", Map.of()))
                .isInstanceOf(ExecutionNotLocalException.class)
                .hasMessageContaining("node-b");

        verify(eventBroadcaster, never()).publish(any(), any());
    }

    /// The run is registered for its whole life and unregistered even when it
    /// fails: a registration left behind would keep this node's sweeper from ever
    /// recovering the execution.
    @Test
    void shouldStartWhenTenantSessionIsNotOnAnotherNode() throws Exception {
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(leaseManager.sessionElsewhere("tenant-1")).thenReturn(Optional.empty());
        when(workflowExecutor.execute(any(), any(), any()))
                .thenThrow(new RuntimeException("stop after dispatch"));

        String executionId = service.startExecution("tenant-1", "wf-1", Map.of()).executionId();

        assertThat(executionComplete.await(5, TimeUnit.SECONDS)).isTrue();
        verify(eventBroadcaster).publish(any(), any(ExecutionEvent.ExecutionStarted.class));
        InOrder run = inOrder(leaseManager, workflowExecutor);
        run.verify(leaseManager).beginRun("tenant-1", executionId);
        run.verify(workflowExecutor).execute(any(), any(), any());
        run.verify(leaseManager).endRun("tenant-1", executionId);
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
