package io.hensu.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.server.service.WorkflowService.ExecutionNotFoundException;
import io.hensu.server.service.WorkflowService.ExecutionOutput;
import io.hensu.server.service.WorkflowService.ExecutionStatus;
import io.hensu.server.service.WorkflowService.ExecutionSummary;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WorkflowServiceTest {

    private WorkflowExecutor workflowExecutor;
    private WorkflowStateRepository stateRepository;
    private ExecutionEventBroadcaster eventBroadcaster;
    private WorkflowRepository workflowRepository;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        workflowExecutor = mock(WorkflowExecutor.class);
        stateRepository = mock(WorkflowStateRepository.class);
        eventBroadcaster = mock(ExecutionEventBroadcaster.class);
        workflowRepository = mock(WorkflowRepository.class);
        service =
                new WorkflowService(
                        workflowExecutor, stateRepository, eventBroadcaster, workflowRepository);
    }

    private HensuSnapshot createSnapshot(
            String workflowId, String executionId, String currentNodeId) {
        return new HensuSnapshot(
                workflowId, executionId, currentNodeId, Map.of(), null, null, Instant.now(), null);
    }

    @Nested
    class GetExecutionStatus {

        @Test
        void shouldReturnStatusForExistingExecution() {
            HensuSnapshot snapshot = createSnapshot("wf-1", "exec-1", "node-1");
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(snapshot));

            ExecutionStatus status = service.getExecutionStatus("tenant-1", "exec-1");

            assertThat(status.executionId()).isEqualTo("exec-1");
            assertThat(status.workflowId()).isEqualTo("wf-1");
            assertThat(status.status()).isEqualTo("PAUSED");
            assertThat(status.currentNodeId()).isEqualTo("node-1");
        }

        @Test
        void shouldReturnCompletedStatusWhenNoCurrentNode() {
            HensuSnapshot snapshot = createSnapshot("wf-1", "exec-1", null);
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(snapshot));

            ExecutionStatus status = service.getExecutionStatus("tenant-1", "exec-1");

            assertThat(status.status()).isEqualTo("COMPLETED");
        }

        @Test
        void shouldThrowWhenExecutionNotFound() {
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getExecutionStatus("tenant-1", "exec-1"))
                    .isInstanceOf(ExecutionNotFoundException.class)
                    .hasMessageContaining("exec-1");
        }

        @Test
        void shouldRejectNullTenantId() {
            assertThatThrownBy(() -> service.getExecutionStatus(null, "exec-1"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullExecutionId() {
            assertThatThrownBy(() -> service.getExecutionStatus("tenant-1", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class ListPausedExecutions {

        @Test
        void shouldReturnPausedExecutions() {
            List<HensuSnapshot> snapshots =
                    List.of(
                            createSnapshot("wf-1", "exec-1", "node-1"),
                            createSnapshot("wf-2", "exec-2", "node-2"));
            when(stateRepository.findPaused("tenant-1")).thenReturn(snapshots);

            List<ExecutionSummary> paused = service.listPausedExecutions("tenant-1");

            assertThat(paused).hasSize(2);
            assertThat(paused.get(0).executionId()).isEqualTo("exec-1");
            assertThat(paused.get(1).executionId()).isEqualTo("exec-2");
        }

        @Test
        void shouldReturnEmptyListWhenNoPausedExecutions() {
            when(stateRepository.findPaused("tenant-1")).thenReturn(List.of());

            List<ExecutionSummary> paused = service.listPausedExecutions("tenant-1");

            assertThat(paused).isEmpty();
        }
    }

    @Nested
    class ResumeExecution {

        @Test
        void shouldLoadStateForResume() throws Exception {
            HensuSnapshot snapshot = createSnapshot("wf-1", "exec-1", "node-1");
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(snapshot));
            when(workflowRepository.findById("tenant-1", "wf-1"))
                    .thenReturn(Optional.of(mock(Workflow.class)));
            when(workflowExecutor.executeFrom(any(), any())).thenReturn(null);

            service.resumeExecution("tenant-1", "exec-1", null);

            verify(stateRepository).findByExecutionId("tenant-1", "exec-1");
        }

        @Test
        void shouldThrowWhenExecutionNotFoundForResume() {
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resumeExecution("tenant-1", "exec-1", null))
                    .isInstanceOf(ExecutionNotFoundException.class);
        }
    }

    @Nested
    class GetPendingPlan {

        @Test
        void shouldReturnEmptyWhenNoActivePlan() {
            HensuSnapshot snapshot = createSnapshot("wf-1", "exec-1", "node-1");
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(snapshot));

            Optional<WorkflowService.PlanInfo> plan = service.getPendingPlan("tenant-1", "exec-1");

            assertThat(plan).isEmpty();
        }

        @Test
        void shouldThrowWhenExecutionNotFound() {
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPendingPlan("tenant-1", "exec-1"))
                    .isInstanceOf(ExecutionNotFoundException.class);
        }
    }

    @Nested
    class GetExecutionResult {

        private HensuSnapshot snapshotWithContext(Map<String, Object> context, String reason) {
            return new HensuSnapshot(
                    "wf-1", "exec-1", null, context, null, null, Instant.now(), reason);
        }

        @Test
        void shouldFilterInternalKeysFromOutput() {
            Map<String, Object> mixedContext = new HashMap<>();
            mixedContext.put("_tenant_id", "tenant-1");
            mixedContext.put("_execution_id", "exec-1");
            mixedContext.put("_last_output", "internal routing value");
            mixedContext.put("summary", "Order processed successfully");
            mixedContext.put("approved", true);
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(snapshotWithContext(mixedContext, "completed")));

            ExecutionOutput output = service.getExecutionResult("tenant-1", "exec-1");

            assertThat(output.output()).doesNotContainKey("_tenant_id");
            assertThat(output.output()).doesNotContainKey("_execution_id");
            assertThat(output.output()).doesNotContainKey("_last_output");
            assertThat(output.output()).containsEntry("summary", "Order processed successfully");
            assertThat(output.output()).containsEntry("approved", true);
        }

        @Test
        void shouldReturnEmptyOutputWhenAllContextKeysAreInternal() {
            Map<String, Object> internalOnly = new HashMap<>();
            internalOnly.put("_tenant_id", "tenant-1");
            internalOnly.put("_execution_id", "exec-1");
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(snapshotWithContext(internalOnly, "completed")));

            ExecutionOutput output = service.getExecutionResult("tenant-1", "exec-1");

            assertThat(output.output()).isEmpty();
        }

        @Test
        void shouldReturnCorrectStatusAndIdentifiers() {
            Map<String, Object> context = new HashMap<>();
            context.put("result", "done");
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(snapshotWithContext(context, "completed")));

            ExecutionOutput output = service.getExecutionResult("tenant-1", "exec-1");

            assertThat(output.executionId()).isEqualTo("exec-1");
            assertThat(output.workflowId()).isEqualTo("wf-1");
            assertThat(output.status()).isEqualTo("COMPLETED");
            assertThat(output.output()).containsKey("result");
        }

        @Test
        void shouldReportPausedStatusForNonCompletedSnapshot() {
            Map<String, Object> context = new HashMap<>();
            context.put("step", "validation");
            // currentNodeId is non-null and reason is not "completed" → PAUSED
            HensuSnapshot paused =
                    new HensuSnapshot(
                            "wf-1",
                            "exec-1",
                            "validation-node",
                            context,
                            null,
                            null,
                            Instant.now(),
                            "checkpoint");
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(paused));

            ExecutionOutput output = service.getExecutionResult("tenant-1", "exec-1");

            assertThat(output.status()).isEqualTo("PAUSED");
        }

        @Test
        void shouldThrowWhenExecutionNotFound() {
            when(stateRepository.findByExecutionId("tenant-1", "missing"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getExecutionResult("tenant-1", "missing"))
                    .isInstanceOf(ExecutionNotFoundException.class)
                    .hasMessageContaining("missing");
        }
    }

    @Nested
    class ConstructorValidation {

        @Test
        void shouldRejectNullWorkflowExecutor() {
            assertThatThrownBy(
                            () ->
                                    new WorkflowService(
                                            null,
                                            stateRepository,
                                            eventBroadcaster,
                                            workflowRepository))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("workflowExecutor");
        }

        @Test
        void shouldRejectNullStateRepository() {
            assertThatThrownBy(
                            () ->
                                    new WorkflowService(
                                            workflowExecutor,
                                            null,
                                            eventBroadcaster,
                                            workflowRepository))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("stateRepository");
        }

        @Test
        void shouldRejectNullEventBroadcaster() {
            assertThatThrownBy(
                            () ->
                                    new WorkflowService(
                                            workflowExecutor,
                                            stateRepository,
                                            null,
                                            workflowRepository))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("eventBroadcaster");
        }

        @Test
        void shouldRejectNullWorkflowRepository() {
            assertThatThrownBy(
                            () ->
                                    new WorkflowService(
                                            workflowExecutor,
                                            stateRepository,
                                            eventBroadcaster,
                                            null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("workflowRepository");
        }
    }
}
