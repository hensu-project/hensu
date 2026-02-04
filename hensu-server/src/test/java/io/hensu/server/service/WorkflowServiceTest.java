package io.hensu.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.storage.workflow.WorkflowStateRepository;
import io.hensu.server.service.WorkflowService.ExecutionNotFoundException;
import io.hensu.server.service.WorkflowService.ExecutionStatus;
import io.hensu.server.service.WorkflowService.ExecutionSummary;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import java.time.Instant;
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
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        workflowExecutor = mock(WorkflowExecutor.class);
        stateRepository = mock(WorkflowStateRepository.class);
        eventBroadcaster = mock(ExecutionEventBroadcaster.class);
        service = new WorkflowService(workflowExecutor, stateRepository, eventBroadcaster);
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
        void shouldLoadStateForResume() {
            HensuSnapshot snapshot = createSnapshot("wf-1", "exec-1", "node-1");
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(snapshot));

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
    class ConstructorValidation {

        @Test
        void shouldRejectNullWorkflowExecutor() {
            assertThatThrownBy(() -> new WorkflowService(null, stateRepository, eventBroadcaster))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("workflowExecutor");
        }

        @Test
        void shouldRejectNullStateRepository() {
            assertThatThrownBy(() -> new WorkflowService(workflowExecutor, null, eventBroadcaster))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("stateRepository");
        }

        @Test
        void shouldRejectNullEventBroadcaster() {
            assertThatThrownBy(() -> new WorkflowService(workflowExecutor, stateRepository, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("eventBroadcaster");
        }
    }
}
