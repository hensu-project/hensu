package io.hensu.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.ExecutionJournal;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.InMemoryWorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.execution.RunRegistry;
import io.hensu.server.persistence.InMemoryExecutionStore;
import io.hensu.server.persistence.InMemoryToolAuditRepository;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Starting an execution: accepted by whichever node the request reached, durable before
/// the request is answered, and settled under the claim the start took.
class WorkflowExecutionServiceTest {

    private InMemoryWorkflowStateRepository snapshots;
    private InMemoryExecutionStore store;
    private WorkflowExecutor executor;
    private RunRegistry runs;
    private WorkflowExecutionService service;

    @BeforeEach
    void setUp() {
        snapshots = new InMemoryWorkflowStateRepository();
        store = new InMemoryExecutionStore(snapshots, (_, _) -> {});
        executor = mock(WorkflowExecutor.class);
        Workflow workflow = mock(Workflow.class);
        when(workflow.getId()).thenReturn("wf-1");
        when(workflow.getStartNode()).thenReturn("start");
        WorkflowRegistryService registry = mock(WorkflowRegistryService.class);
        when(registry.getWorkflow("t1", "wf-1")).thenReturn(workflow);
        ExecutionEventBroadcaster events = new ExecutionEventBroadcaster();
        runs = new RunRegistry(store, Duration.ofSeconds(30));
        ExecutionRunner runner =
                new ExecutionRunner(
                        executor, store, runs, events, new InMemoryToolAuditRepository());
        service = new WorkflowExecutionService(store, runner, events, registry);
    }

    @Test
    void writesTheExecutionUnderItsFirstClaimBeforeAnswering() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(executor.executeFrom(any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            release.await(5, TimeUnit.SECONDS);
                            HensuState state = invocation.getArgument(1);
                            return new ExecutionResult.Completed(state, ExitStatus.SUCCESS);
                        });

        ExecutionStartResult result = service.startExecution("t1", "wf-1", Map.of("k", "v"), null);

        // The run has not finished, yet the execution is already durable: a node dying now
        // leaves an execution that is picked up, not one that never existed.
        Optional<HensuSnapshot> written = store.findByExecutionId("t1", result.executionId());
        assertThat(written).isPresent();
        assertThat(written.get().context())
                .containsEntry("_execution_id", result.executionId())
                .containsEntry("_tenant_id", "t1")
                .containsEntry("k", "v");
        release.countDown();
        assertThat(awaitReason(result.executionId())).isEqualTo("completed");
    }

    @Test
    void recordsNothingWhenTheRunIsPreempted() throws Exception {
        when(executor.executeFrom(any(), any(), any(), any()))
                .thenThrow(new RunPreemptedException("claim superseded"));

        ExecutionStartResult result = service.startExecution("t1", "wf-1", Map.of(), null);

        assertThat(awaitRunEnded()).as("run ended").isTrue();
        // A failure written here would be refused, and false if it were not: another run
        // holds the execution.
        assertThat(snapshots.findByExecutionId("t1", result.executionId()))
                .get()
                .extracting(HensuSnapshot::checkpointReason)
                .isEqualTo("checkpoint");
    }

    @Test
    void recordsTheFailureWhenTheRunFailsBeforeItsFirstCheckpoint() throws Exception {
        when(executor.executeFrom(
                        any(), any(), any(ExecutionListener.class), any(ExecutionJournal.class)))
                .thenThrow(new IllegalStateException("agent missing"));

        ExecutionStartResult result = service.startExecution("t1", "wf-1", Map.of(), null);

        assertThat(awaitReason(result.executionId())).isEqualTo("failed");
    }

    @Test
    void aRetriedStartAnswersWithTheExecutionItCreatedAndRunsItOnce() throws Exception {
        when(executor.executeFrom(any(), any(), any(), any()))
                .thenAnswer(
                        invocation ->
                                new ExecutionResult.Completed(
                                        invocation.getArgument(1), ExitStatus.SUCCESS));

        ExecutionStartResult first =
                service.startExecution("t1", "wf-1", Map.of("order", "42"), "start-1");
        ExecutionStartResult retry =
                service.startExecution("t1", "wf-1", Map.of("order", "42"), "start-1");

        assertThat(retry.executionId()).isEqualTo(first.executionId());
        assertThat(awaitReason(first.executionId())).isEqualTo("completed");
        verify(executor, times(1)).executeFrom(any(), any(), any(), any());

        // The key promised one execution for one request; a different request must not be
        // told it ran, nor start a second execution under the key.
        assertThatThrownBy(
                        () ->
                                service.startExecution(
                                        "t1", "wf-1", Map.of("order", "43"), "start-1"))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .hasMessageContaining(first.executionId());
        verify(executor, times(1)).executeFrom(any(), any(), any(), any());
    }

    private String awaitReason(String executionId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String reason =
                    snapshots
                            .findByExecutionId("t1", executionId)
                            .map(HensuSnapshot::checkpointReason)
                            .orElse(null);
            if (reason != null && !reason.equals("checkpoint")) {
                return reason;
            }
            Thread.sleep(20);
        }
        return null;
    }

    /// The start registers its run before answering, and the run unregisters only after
    /// whatever it records on the way out – so an empty registry means nothing more comes.
    private boolean awaitRunEnded() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (runs.size() == 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
