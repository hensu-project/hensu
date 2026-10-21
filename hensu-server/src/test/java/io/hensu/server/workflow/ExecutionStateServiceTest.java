package io.hensu.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.resume.ResumeInput;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.persistence.ExecutionLeaseManager;
import io.hensu.server.persistence.ExecutionLeaseManager.ClaimOutcome;
import io.hensu.server.persistence.InMemoryToolAuditRepository;
import io.hensu.server.persistence.LeaseLostException;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExecutionStateServiceTest {

    private WorkflowExecutor workflowExecutor;
    private WorkflowStateRepository stateRepository;
    private WorkflowRegistryService registryService;
    private ExecutionLeaseManager leaseManager;
    private ExecutionEventBroadcaster eventBroadcaster;
    private ExecutionStateService service;

    @BeforeEach
    void setUp() {
        workflowExecutor = mock(WorkflowExecutor.class);
        stateRepository = mock(WorkflowStateRepository.class);
        registryService = mock(WorkflowRegistryService.class);
        leaseManager = mock(ExecutionLeaseManager.class);
        eventBroadcaster = mock(ExecutionEventBroadcaster.class);
        when(leaseManager.tryClaim(any(), any())).thenReturn(new ClaimOutcome.Claimed());

        // Allow execution to run within runAs
        try {
            doAnswer(invocation -> ((Callable<?>) invocation.getArgument(1)).call())
                    .when(eventBroadcaster)
                    .runAs(any(), any());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        service =
                new ExecutionStateService(
                        workflowExecutor,
                        stateRepository,
                        registryService,
                        leaseManager,
                        eventBroadcaster,
                        new InMemoryToolAuditRepository());
    }

    private HensuSnapshot pausedSnapshot() {
        return new HensuSnapshot(
                "wf-1",
                "exec-1",
                "node-1",
                new HashMap<>(Map.of("k", "v")),
                Map.of(),
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
    void shouldApplyDecisionModificationsToContextBeforeExecuting() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenReturn(new ExecutionResult.Completed(completedState(), ExitStatus.SUCCESS));

        ResumeInput decision =
                new ResumeInput.ApplyContextEdits(
                        Map.of("approved", true, "reviewer_note", "looks good"));

        service.resumeExecution("tenant-1", "exec-1", decision);

        ArgumentCaptor<HensuState> stateCaptor = ArgumentCaptor.forClass(HensuState.class);
        verify(workflowExecutor).executeFrom(any(), stateCaptor.capture(), any());
        Map<String, Object> ctx = stateCaptor.getValue().getContext();
        assertThat(ctx).containsEntry("approved", true);
        assertThat(ctx).containsEntry("reviewer_note", "looks good");
        assertThat(ctx).containsEntry("k", "v");
    }

    /// Was an `IllegalStateException` (a 500); a lease held elsewhere is a routing
    /// fact, not a server fault, so it is now the 409-mapped `ExecutionNotLocalException`.
    @Test
    void shouldRejectResumeAndSkipExecutorWhenLeaseHeldByAnotherNode() throws Exception {
        when(leaseManager.tryClaim("tenant-1", "exec-1"))
                .thenReturn(new ClaimOutcome.LeasedElsewhere());

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                .isInstanceOf(ExecutionNotLocalException.class)
                .hasMessageContaining("Execution owned by another node")
                .hasMessageContaining("exec-1");

        verify(stateRepository, never()).findByExecutionId(any(), any());
        verify(workflowExecutor, never()).executeFrom(any(), any(), any());
        verify(leaseManager, never()).release(any(), any());
    }

    /// A resume here would run without the tenant's tools, so it is refused and
    /// names the node that can serve it, rather than accepted and quietly degraded.
    @Test
    void shouldRejectResumeWhenTenantSessionIsOnAnotherNode() throws Exception {
        when(leaseManager.tryClaim("tenant-1", "exec-1"))
                .thenReturn(new ClaimOutcome.SessionElsewhere("node-b"));

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                .isInstanceOf(ExecutionNotLocalException.class)
                .hasMessageContaining("node-b");

        verify(workflowExecutor, never()).executeFrom(any(), any(), any());
        verify(leaseManager, never()).release(any(), any());
    }

    /// A typo'd id used to come back as 409 "owned by another node", sending an
    /// operator to chase a routing problem that does not exist.
    @Test
    void shouldReportMissingExecutionAsNotFound() {
        when(leaseManager.tryClaim("tenant-1", "exec-typo"))
                .thenReturn(new ClaimOutcome.NotFound());

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-typo", null))
                .isInstanceOf(ExecutionNotFoundException.class);

        verify(leaseManager, never()).release(any(), any());
    }

    /// Releasing here would unregister the run that is live on this node and let
    /// this node's sweeper resume a second copy beside it.
    @Test
    void shouldRejectResumeOfRunLiveHereWithoutReleasingIt() throws Exception {
        when(leaseManager.tryClaim("tenant-1", "exec-1"))
                .thenReturn(new ClaimOutcome.AlreadyRunning());

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                .isInstanceOf(ExecutionNotLocalException.class)
                .hasMessageContaining("already running");

        verify(workflowExecutor, never()).executeFrom(any(), any(), any());
        verify(leaseManager, never()).release(any(), any());
    }

    /// The run saved its terminal state, which releases the row, and then the
    /// event stream failed. The `failed` save is refused by the fence; that
    /// refusal must not replace the error being reported, nor rewrite a completed
    /// execution as failed.
    @Test
    void shouldReportOriginalFailureWhenFailedSaveIsRefusedAfterTerminalSave() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenReturn(new ExecutionResult.Completed(completedState(), ExitStatus.SUCCESS));
        doThrow(new IllegalStateException("event stream closed"))
                .when(eventBroadcaster)
                .publish(eq("exec-1"), any(ExecutionEvent.ExecutionCompleted.class));
        doAnswer(
                        invocation -> {
                            HensuSnapshot snapshot = invocation.getArgument(1);
                            if ("failed".equals(snapshot.checkpointReason())) {
                                throw new LeaseLostException("exec-1");
                            }
                            return null;
                        })
                .when(stateRepository)
                .save(eq("tenant-1"), any());

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                .isInstanceOf(WorkflowExecutionException.class)
                .cause()
                .hasMessage("event stream closed")
                .satisfies(
                        cause ->
                                assertThat(cause.getSuppressed())
                                        .singleElement()
                                        .isInstanceOf(LeaseLostException.class));
        verify(eventBroadcaster).publish(eq("exec-1"), any(ExecutionEvent.ExecutionError.class));
    }

    /// A resumed run that loses its lease mid-flight stops and is reported as not
    /// local; it records no failure over the state the claiming node is writing.
    @Test
    void shouldStopWithoutRecordingFailureWhenLeaseIsLost() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenThrow(new LeaseLostException("exec-1"));

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                .isInstanceOf(ExecutionNotLocalException.class);

        verify(stateRepository, never()).save(any(), any());
    }

    /// Resume used to bind a tenant with no MCP endpoint, so the resumed run saw an
    /// empty tool catalog. It must bind the same endpoint the start path binds.
    @Test
    void shouldBindTenantMcpEndpointOnResume() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        AtomicReference<TenantInfo> bound = new AtomicReference<>();
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenAnswer(
                        _ -> {
                            bound.set(TenantContext.current());
                            return new ExecutionResult.Completed(
                                    completedState(), ExitStatus.SUCCESS);
                        });

        service.resumeExecution("tenant-1", "exec-1", null);

        assertThat(bound.get().hasMcp()).isTrue();
        assertThat(bound.get().mcpEndpoint()).isEqualTo("sse://tenant-1");
    }

    @Test
    void shouldPersistFailedSnapshotWhenExecutorReturnsSuccess() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        HensuState successState = completedState();
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenReturn(new ExecutionResult.Success(successState));

        service.resumeExecution("tenant-1", "exec-1", null);

        ArgumentCaptor<HensuSnapshot> savedCaptor = ArgumentCaptor.forClass(HensuSnapshot.class);
        verify(stateRepository).save(eq("tenant-1"), savedCaptor.capture());
        assertThat(savedCaptor.getValue().checkpointReason()).isEqualTo("failed");

        verify(eventBroadcaster).publish(eq("exec-1"), any(ExecutionEvent.ExecutionError.class));
    }

    @Test
    void shouldReleaseLeaseEvenWhenExecutorThrows() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenThrow(new RuntimeException("executor crashed"));

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                .isInstanceOf(WorkflowExecutionException.class);

        verify(leaseManager).release("tenant-1", "exec-1");
    }

    @Test
    void shouldPersistFailedSnapshotWhenExecutorThrows() throws Exception {
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(pausedSnapshot()));
        when(registryService.getWorkflow("tenant-1", "wf-1")).thenReturn(mock(Workflow.class));
        when(workflowExecutor.executeFrom(any(), any(), any()))
                .thenThrow(new RuntimeException("agent provider unavailable"));

        assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                .isInstanceOf(WorkflowExecutionException.class);

        ArgumentCaptor<HensuSnapshot> savedCaptor = ArgumentCaptor.forClass(HensuSnapshot.class);
        verify(stateRepository).save(eq("tenant-1"), savedCaptor.capture());
        assertThat(savedCaptor.getValue().checkpointReason()).isEqualTo("failed");

        verify(eventBroadcaster).publish(eq("exec-1"), any(ExecutionEvent.ExecutionError.class));
    }
}
