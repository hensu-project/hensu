package io.hensu.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.plan.PlanSnapshot;
import io.hensu.core.plan.PlanSnapshot.PlannedStepSnapshot;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.WorkflowStateRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExecutionQueryServiceTest {

    private WorkflowStateRepository stateRepository;
    private ExecutionQueryService service;

    @BeforeEach
    void setUp() {
        stateRepository = mock(WorkflowStateRepository.class);
        service = new ExecutionQueryService(stateRepository);
    }

    private HensuSnapshot snapshot(String workflowId, String executionId, String currentNodeId) {
        return new HensuSnapshot(
                workflowId, executionId, currentNodeId, Map.of(), null, null, Instant.now(), null);
    }

    @Nested
    class GetExecutionStatus {

        @Test
        void shouldMapSnapshotFieldsIntoStatus() {
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(snapshot("wf-1", "exec-1", "node-1")));

            ExecutionStatus status = service.getExecutionStatus("tenant-1", "exec-1");

            assertThat(status.executionId()).isEqualTo("exec-1");
            assertThat(status.workflowId()).isEqualTo("wf-1");
            assertThat(status.status()).isEqualTo("PAUSED");
            assertThat(status.currentNodeId()).isEqualTo("node-1");
        }

        @Test
        void shouldReportCompletedWhenSnapshotHasNoCurrentNode() {
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(snapshot("wf-1", "exec-1", null)));

            ExecutionStatus status = service.getExecutionStatus("tenant-1", "exec-1");

            assertThat(status.status()).isEqualTo("COMPLETED");
        }

        @Test
        void shouldThrowExecutionNotFoundWhenSnapshotMissing() {
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getExecutionStatus("tenant-1", "exec-1"))
                    .isInstanceOf(ExecutionNotFoundException.class)
                    .hasMessageContaining("exec-1");
        }
    }

    @Nested
    class ListPausedExecutions {

        @Test
        void shouldMapSnapshotsIntoSummaries() {
            when(stateRepository.findPaused("tenant-1"))
                    .thenReturn(
                            List.of(
                                    snapshot("wf-1", "exec-1", "node-1"),
                                    snapshot("wf-2", "exec-2", "node-2")));

            List<ExecutionSummary> paused = service.listPausedExecutions("tenant-1");

            assertThat(paused).hasSize(2);
            assertThat(paused.getFirst().executionId()).isEqualTo("exec-1");
            assertThat(paused.get(0).workflowId()).isEqualTo("wf-1");
            assertThat(paused.get(0).currentNodeId()).isEqualTo("node-1");
            assertThat(paused.get(1).executionId()).isEqualTo("exec-2");
        }
    }

    @Nested
    class GetPendingPlan {

        @Test
        void shouldReturnEmptyWhenSnapshotHasNoActivePlan() {
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(snapshot("wf-1", "exec-1", "node-1")));

            Optional<PlanInfo> plan = service.getPendingPlan("tenant-1", "exec-1");

            assertThat(plan).isEmpty();
        }

        @Test
        void shouldReturnPlanInfoWithCorrectFieldOrderWhenPlanActive() {
            PlanSnapshot active =
                    new PlanSnapshot(
                            "review-node",
                            List.of(
                                    new PlannedStepSnapshot(
                                            0, "tool-a", Map.of(), "first", "PENDING"),
                                    new PlannedStepSnapshot(
                                            1, "tool-b", Map.of(), "second", "PENDING"),
                                    new PlannedStepSnapshot(
                                            2, "tool-c", Map.of(), "third", "PENDING")),
                            1,
                            List.of());
            HensuSnapshot withPlan =
                    new HensuSnapshot(
                            "wf-1",
                            "exec-1",
                            "review-node",
                            Map.of(),
                            null,
                            active,
                            Instant.now(),
                            null);
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(withPlan));

            PlanInfo info = service.getPendingPlan("tenant-1", "exec-1").orElseThrow();

            assertThat(info.planId()).isEqualTo("review-node");
            assertThat(info.totalSteps()).isEqualTo(3);
            assertThat(info.currentStep()).isEqualTo(1);
        }
    }

    @Nested
    class GetExecutionResult {

        private HensuSnapshot withContext(Map<String, Object> context) {
            return new HensuSnapshot(
                    "wf-1", "exec-1", null, context, null, null, Instant.now(), "completed");
        }

        @Test
        void shouldFilterUnderscorePrefixedKeysFromOutput() {
            Map<String, Object> mixed = new HashMap<>();
            mixed.put("_tenant_id", "tenant-1");
            mixed.put("_execution_id", "exec-1");
            mixed.put("_last_output", "internal routing value");
            mixed.put("summary", "Order processed successfully");
            mixed.put("approved", true);
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(withContext(mixed)));

            ExecutionOutput output = service.getExecutionResult("tenant-1", "exec-1");

            assertThat(output.output()).doesNotContainKey("_tenant_id");
            assertThat(output.output()).doesNotContainKey("_execution_id");
            assertThat(output.output()).doesNotContainKey("_last_output");
            assertThat(output.output()).containsEntry("summary", "Order processed successfully");
            assertThat(output.output()).containsEntry("approved", true);
        }

        @Test
        void shouldReturnEmptyMapWhenAllKeysAreInternal() {
            Map<String, Object> internalOnly = new HashMap<>();
            internalOnly.put("_tenant_id", "tenant-1");
            internalOnly.put("_execution_id", "exec-1");
            when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                    .thenReturn(Optional.of(withContext(internalOnly)));

            ExecutionOutput output = service.getExecutionResult("tenant-1", "exec-1");

            assertThat(output.output()).isEmpty();
        }
    }
}
