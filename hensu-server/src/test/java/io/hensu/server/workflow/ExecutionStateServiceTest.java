package io.hensu.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.persistence.ExecutionLeaseManager;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExecutionStateServiceTest {

    private WorkflowExecutor workflowExecutor;
    private WorkflowStateRepository stateRepository;
    private WorkflowRegistryService registryService;
    private ExecutionLeaseManager leaseManager;
    private ExecutionStateService service;

    @BeforeEach
    void setUp() {
        workflowExecutor = mock(WorkflowExecutor.class);
        stateRepository = mock(WorkflowStateRepository.class);
        registryService = mock(WorkflowRegistryService.class);
        leaseManager = mock(ExecutionLeaseManager.class);
        when(leaseManager.tryClaim(any(), any())).thenReturn(true);
        service =
                new ExecutionStateService(
                        workflowExecutor, stateRepository, registryService, leaseManager);
    }

    private HensuSnapshot pausedSnapshot() {
        return new HensuSnapshot(
                "wf-1",
                "exec-1",
                "node-1",
                new HashMap<>(Map.of("k", "v")),
                null,
                null,
                Instant.now(),
                "paused");
    }

    private HensuState completedState() {
        return new HensuState.Builder()
                .workflowId("wf-1")
                .executionId("exec-1")
                .currentNode("done")
                .build();
    }

    @Test
    void shouldThrowExecutionNotFoundWhenSnapshotMissing() {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                .isInstanceOf(ExecutionNotFoundException.class)
                .hasMessageContaining("exec-1");
    }

    @Test
    void shouldApplyDecisionModificationsToContextBeforeExecuting() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenReturn(new ExecutionResult.Completed(completedState(), ExitStatus.SUCCESS));

        ResumeDecision decision =
                new ResumeDecision(true, Map.of("approved", true, "reviewer_note", "looks good"));

        service.resumeExecution("tenant-1", "exec-1", decision);

        ArgumentCaptor<HensuState> stateCaptor = ArgumentCaptor.forClass(HensuState.class);
        verify(workflowExecutor).executeFrom(any(), stateCaptor.capture(), any());
        Map<String, Object> ctx = stateCaptor.getValue().getContext();
        assertThat(ctx).containsEntry("approved", true);
        assertThat(ctx).containsEntry("reviewer_note", "looks good");
        assertThat(ctx).containsEntry("k", "v");
    }

    @Test
    void shouldPersistCompletedSnapshotWhenResumeReturnsCompleted() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenReturn(new ExecutionResult.Completed(completedState(), ExitStatus.SUCCESS));

        service.resumeExecution("tenant-1", "exec-1", null);

        ArgumentCaptor<HensuSnapshot> savedCaptor = ArgumentCaptor.forClass(HensuSnapshot.class);
        verify(stateRepository).save(eq("tenant-1"), savedCaptor.capture());
        assertThat(savedCaptor.getValue().checkpointReason()).isEqualTo("completed");
    }

    @Test
    void shouldPersistPausedSnapshotWhenResumeReturnsPaused() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenReturn(new ExecutionResult.Paused(completedState()));

        service.resumeExecution("tenant-1", "exec-1", null);

        ArgumentCaptor<HensuSnapshot> savedCaptor = ArgumentCaptor.forClass(HensuSnapshot.class);
        verify(stateRepository).save(eq("tenant-1"), savedCaptor.capture());
        assertThat(savedCaptor.getValue().checkpointReason()).isEqualTo("paused");
    }

    @Test
    void shouldPersistRejectedSnapshotWhenResumeReturnsRejected() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenReturn(new ExecutionResult.Rejected("policy violation", completedState()));

        service.resumeExecution("tenant-1", "exec-1", null);

        ArgumentCaptor<HensuSnapshot> savedCaptor = ArgumentCaptor.forClass(HensuSnapshot.class);
        verify(stateRepository).save(eq("tenant-1"), savedCaptor.capture());
        assertThat(savedCaptor.getValue().checkpointReason()).isEqualTo("rejected");
    }

    @Test
    void shouldPersistFailureSnapshotWhenResumeReturnsFailure() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenReturn(
                        new ExecutionResult.Failure(
                                completedState(), new IllegalStateException("boom")));

        service.resumeExecution("tenant-1", "exec-1", null);

        ArgumentCaptor<HensuSnapshot> savedCaptor = ArgumentCaptor.forClass(HensuSnapshot.class);
        verify(stateRepository).save(eq("tenant-1"), savedCaptor.capture());
        assertThat(savedCaptor.getValue().checkpointReason()).isEqualTo("failed");
    }

    @Test
    void shouldWrapExecutorRuntimeExceptionAsWorkflowExecutionException() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenThrow(new RuntimeException("executor crashed"));

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("Resume failed")
                .hasRootCauseMessage("executor crashed");
    }

    @Test
    void shouldWrapRegistryNotFoundAsWorkflowExecutionException() {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1"))
                .thenThrow(new WorkflowNotFoundException("Workflow not found: wf-1"));

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasCauseInstanceOf(WorkflowNotFoundException.class);
    }

    @Test
    void shouldRejectResumeAndSkipExecutorWhenLeaseHeldByAnotherNode() throws Exception {
        // Closes the split-brain window: an API-edge resume must refuse to drive the
        // executor when the lease row is owned by a different node. tryClaim returning
        // false is the only signal the service has — verify we throw and never touch
        // the state repo, executor, or release path.
        when(leaseManager.tryClaim("tenant-1", "exec-1")).thenReturn(false);

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Execution owned by another node")
                .hasMessageContaining("exec-1");

        verify(stateRepository, never()).findByExecutionId(any(), any());
        verify(workflowExecutor, never()).executeFrom(any(), any(), any());
        verify(leaseManager, never()).release(any(), any());
    }

    @Test
    void shouldReleaseLeaseEvenWhenExecutorThrows() throws Exception {
        // Defends against lease leakage on failure. Without the finally block a
        // crashed execution stays locked to this node until the next 60 s recovery
        // sweep, blocking manual retries and masking real outages.
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenThrow(new RuntimeException("executor crashed"));

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                .isInstanceOf(WorkflowExecutionException.class);

        verify(leaseManager).release("tenant-1", "exec-1");
    }
}
