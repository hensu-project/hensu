package io.hensu.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.resume.ResumeInput;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.InMemoryWorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.execution.Claim;
import io.hensu.server.execution.RunRegistry;
import io.hensu.server.persistence.InMemoryExecutionStore;
import io.hensu.server.persistence.InMemoryToolAuditRepository;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/// Resuming a paused execution: any node may serve it, the claim is the store's, and the
/// state is read under the claim.
class ExecutionStateServiceTest {

    private InMemoryWorkflowStateRepository snapshots;
    private InMemoryExecutionStore store;
    private WorkflowExecutor executor;
    private ExecutionStateService service;

    @BeforeEach
    void setUp() {
        snapshots = new InMemoryWorkflowStateRepository();
        store = new InMemoryExecutionStore(snapshots, (_, _) -> {});
        executor = mock(WorkflowExecutor.class);
        WorkflowRegistryService registry = mock(WorkflowRegistryService.class);
        when(registry.getWorkflow("t1", "wf-1")).thenReturn(mock(Workflow.class));
        ExecutionRunner runner =
                new ExecutionRunner(
                        executor,
                        store,
                        new RunRegistry(store, Duration.ofSeconds(30)),
                        new ExecutionEventBroadcaster(),
                        new InMemoryToolAuditRepository());
        service = new ExecutionStateService(store, runner, registry);
    }

    @Test
    void appliesContextEditsAndRunsUnderANewClaim() throws Exception {
        Claim paused = paused(ExecutionPhase.INITIAL);
        when(executor.executeFrom(any(), any(), any(), any()))
                .thenAnswer(
                        invocation ->
                                new ExecutionResult.Completed(
                                        invocation.getArgument(1), ExitStatus.SUCCESS));

        service.resumeExecution(
                "t1", "exec-1", new ResumeInput.ApplyContextEdits(Map.of("approved", true)));

        ArgumentCaptor<HensuState> state = ArgumentCaptor.forClass(HensuState.class);
        verify(executor).executeFrom(any(), state.capture(), any(), any());
        assertThat(state.getValue().getContext())
                .containsEntry("approved", true)
                .containsEntry("k", "v");
        // The resume took a claim of its own: the pausing run's epoch can write nothing.
        assertThat(store.holdsClaim(paused)).isFalse();
        assertThat(reason()).isEqualTo("completed");
    }

    @Test
    void reportsAMissingExecutionAsNotFound() {
        assertThatThrownBy(() -> service.resumeExecution("t1", "missing", ResumeInput.NONE))
                .isInstanceOf(ExecutionNotFoundException.class);
    }

    @Test
    void refusesASecondResumeOfTheSameExecution() throws Exception {
        paused(ExecutionPhase.INITIAL);
        when(executor.executeFrom(any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            // While the first resume runs, a second – on any node – is refused.
                            assertThatThrownBy(
                                            () ->
                                                    service.resumeExecution(
                                                            "t1", "exec-1", ResumeInput.NONE))
                                    .isInstanceOf(ExecutionNotPausedException.class);
                            return new ExecutionResult.Completed(
                                    invocation.getArgument(1), ExitStatus.SUCCESS);
                        });

        service.resumeExecution("t1", "exec-1", ResumeInput.NONE);

        verify(executor).executeFrom(any(), any(), any(), any());
    }

    @Test
    void leavesTheExecutionPausedWhenTheResumeInputDoesNotFitItsPhase() throws Exception {
        ExecutionPhase awaiting =
                new ExecutionPhase.Awaiting("review", "reviewer", null, "corr-1", Instant.now());
        paused(awaiting);

        assertThatThrownBy(
                        () ->
                                service.resumeExecution(
                                        "t1",
                                        "exec-1",
                                        new ResumeInput.ApplyContextEdits(Map.of("x", 1))))
                .isInstanceOf(IllegalArgumentException.class);

        verify(executor, never()).executeFrom(any(), any(), any(), any());
        assertThat(store.resume("t1", "exec-1")).isPresent();
    }

    @Test
    void recordsNothingWhenTheResumedRunIsPreempted() throws Exception {
        paused(ExecutionPhase.INITIAL);
        when(executor.executeFrom(any(), any(), any(), any()))
                .thenThrow(new RunPreemptedException("claim superseded"));

        service.resumeExecution("t1", "exec-1", ResumeInput.NONE);

        assertThat(reason()).isEqualTo("paused");
    }

    @Test
    void recordsTheFailureWhenTheResumedRunFails() throws Exception {
        paused(ExecutionPhase.INITIAL);
        when(executor.executeFrom(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.resumeExecution("t1", "exec-1", ResumeInput.NONE))
                .isInstanceOf(WorkflowExecutionException.class);
        assertThat(reason()).isEqualTo("failed");
    }

    @Test
    void recordsAnIntermediateSuccessAsAFailure() throws Exception {
        paused(ExecutionPhase.INITIAL);
        when(executor.executeFrom(any(), any(), any(), any()))
                .thenAnswer(invocation -> new ExecutionResult.Success(invocation.getArgument(1)));

        service.resumeExecution("t1", "exec-1", ResumeInput.NONE);

        assertThat(reason()).isEqualTo("failed");
    }

    private Claim paused(ExecutionPhase phase) {
        Claim claim = store.start("t1", snapshot(phase, "checkpoint"));
        store.pause(claim, snapshot(phase, "paused"));
        return claim;
    }

    private String reason() {
        return snapshots
                .findByExecutionId("t1", "exec-1")
                .map(HensuSnapshot::checkpointReason)
                .orElse(null);
    }

    private static HensuSnapshot snapshot(ExecutionPhase phase, String reason) {
        return new HensuSnapshot(
                "wf-1",
                "exec-1",
                "node-1",
                new HashMap<>(Map.of("k", "v")),
                Map.of(),
                null,
                phase,
                Instant.now(),
                reason,
                0);
    }
}
