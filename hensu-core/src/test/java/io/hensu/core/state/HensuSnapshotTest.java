package io.hensu.core.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.plan.PlanSnapshot;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HensuSnapshotTest {

    @Nested
    class ConstructorValidation {

        @Test
        void shouldThrowWhenWorkflowIdIsNull() {
            assertThatThrownBy(
                            () ->
                                    new HensuSnapshot(
                                            null,
                                            "exec-1",
                                            "node-1",
                                            Map.of(),
                                            new ExecutionHistory(),
                                            null,
                                            Instant.now(),
                                            null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("workflowId");
        }

        @Test
        void shouldThrowWhenExecutionIdIsNull() {
            assertThatThrownBy(
                            () ->
                                    new HensuSnapshot(
                                            "workflow-1",
                                            null,
                                            "node-1",
                                            Map.of(),
                                            new ExecutionHistory(),
                                            null,
                                            Instant.now(),
                                            null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("executionId");
        }

        @Test
        void shouldAcceptNullCurrentNodeId() {
            HensuSnapshot snapshot =
                    new HensuSnapshot(
                            "workflow-1",
                            "exec-1",
                            null,
                            Map.of(),
                            new ExecutionHistory(),
                            null,
                            Instant.now(),
                            null);

            assertThat(snapshot.currentNodeId()).isNull();
            assertThat(snapshot.isCompleted()).isTrue();
        }

        @Test
        void shouldDefaultContextToEmptyMap() {
            HensuSnapshot snapshot =
                    new HensuSnapshot(
                            "workflow-1",
                            "exec-1",
                            "node-1",
                            null,
                            new ExecutionHistory(),
                            null,
                            Instant.now(),
                            null);

            assertThat(snapshot.context()).isNotNull().isEmpty();
        }

        @Test
        void shouldDefaultHistoryToEmptyHistory() {
            HensuSnapshot snapshot =
                    new HensuSnapshot(
                            "workflow-1",
                            "exec-1",
                            "node-1",
                            Map.of(),
                            null,
                            null,
                            Instant.now(),
                            null);

            assertThat(snapshot.history()).isNotNull();
            assertThat(snapshot.history().getSteps()).isEmpty();
        }

        @Test
        void shouldDefaultCreatedAtToNow() {
            Instant before = Instant.now();
            HensuSnapshot snapshot =
                    new HensuSnapshot(
                            "workflow-1",
                            "exec-1",
                            "node-1",
                            Map.of(),
                            new ExecutionHistory(),
                            null,
                            null,
                            null);
            Instant after = Instant.now();

            assertThat(snapshot.createdAt()).isNotNull();
            assertThat(snapshot.createdAt()).isBetween(before, after.plusMillis(1));
        }

        @Test
        void shouldMakeDefensiveCopyOfContext() {
            Map<String, Object> originalContext = new java.util.HashMap<>();
            originalContext.put("key", "value");

            HensuSnapshot snapshot =
                    new HensuSnapshot(
                            "workflow-1",
                            "exec-1",
                            "node-1",
                            originalContext,
                            new ExecutionHistory(),
                            null,
                            Instant.now(),
                            null);

            originalContext.put("newKey", "newValue");

            assertThat(snapshot.context()).doesNotContainKey("newKey");
        }
    }

    @Nested
    class StaticFactoryMethods {

        @Test
        void shouldCreateFromStateWithoutReason() {
            HensuState state = createTestState();
            HensuSnapshot snapshot = HensuSnapshot.from(state);

            assertThat(snapshot.workflowId()).isEqualTo("workflow-1");
            assertThat(snapshot.executionId()).isEqualTo("exec-1");
            assertThat(snapshot.currentNodeId()).isEqualTo("start");
            assertThat(snapshot.checkpointReason()).isNull();
        }

        @Test
        void shouldCreateFromStateWithReason() {
            HensuState state = createTestState();
            HensuSnapshot snapshot = HensuSnapshot.from(state, "user-pause");

            assertThat(snapshot.checkpointReason()).isEqualTo("user-pause");
        }

        @Test
        void shouldCreateFromStateWithPlanSnapshot() {
            HensuState state = createTestState();
            PlanSnapshot planSnapshot = PlanSnapshot.empty("node-1");

            HensuSnapshot snapshot = HensuSnapshot.from(state, planSnapshot, "plan-checkpoint");

            assertThat(snapshot.hasActivePlan()).isTrue();
            assertThat(snapshot.activePlan()).isSameAs(planSnapshot);
            assertThat(snapshot.checkpointReason()).isEqualTo("plan-checkpoint");
        }

        @Test
        void shouldThrowWhenStateIsNull() {
            assertThatThrownBy(() -> HensuSnapshot.from(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldThrowWhenPlanSnapshotIsNullInOverload() {
            HensuState state = createTestState();
            assertThatThrownBy(() -> HensuSnapshot.from(state, (PlanSnapshot) null, "reason"))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class StateRestoration {

        @Test
        void shouldRestoreStateFromSnapshot() {
            HensuSnapshot snapshot =
                    new HensuSnapshot(
                            "workflow-1",
                            "exec-1",
                            "current-node",
                            Map.of("key", "value"),
                            new ExecutionHistory(),
                            null,
                            Instant.now(),
                            "test-checkpoint");

            HensuState restored = snapshot.toState();

            assertThat(restored.getWorkflowId()).isEqualTo("workflow-1");
            assertThat(restored.getExecutionId()).isEqualTo("exec-1");
            assertThat(restored.getCurrentNode()).isEqualTo("current-node");
            assertThat(restored.getContext()).containsEntry("key", "value");
        }
    }

    @Nested
    class QueryMethods {

        @Test
        void shouldDetectActivePlan() {
            PlanSnapshot planSnapshot = PlanSnapshot.empty("node-1");
            HensuSnapshot withPlan =
                    new HensuSnapshot(
                            "workflow-1",
                            "exec-1",
                            "node-1",
                            Map.of(),
                            new ExecutionHistory(),
                            planSnapshot,
                            Instant.now(),
                            null);

            HensuSnapshot withoutPlan =
                    new HensuSnapshot(
                            "workflow-1",
                            "exec-1",
                            "node-1",
                            Map.of(),
                            new ExecutionHistory(),
                            null,
                            Instant.now(),
                            null);

            assertThat(withPlan.hasActivePlan()).isTrue();
            assertThat(withoutPlan.hasActivePlan()).isFalse();
        }

        @Test
        void shouldDetectCompletedState() {
            HensuSnapshot completed =
                    new HensuSnapshot(
                            "workflow-1",
                            "exec-1",
                            null,
                            Map.of(),
                            new ExecutionHistory(),
                            null,
                            Instant.now(),
                            null);

            HensuSnapshot inProgress =
                    new HensuSnapshot(
                            "workflow-1",
                            "exec-1",
                            "node-1",
                            Map.of(),
                            new ExecutionHistory(),
                            null,
                            Instant.now(),
                            null);

            assertThat(completed.isCompleted()).isTrue();
            assertThat(inProgress.isCompleted()).isFalse();
        }
    }

    private static HensuState createTestState() {
        return new HensuState.Builder()
                .executionId("exec-1")
                .workflowId("workflow-1")
                .currentNode("start")
                .context(Map.of("input", "test"))
                .history(new ExecutionHistory())
                .build();
    }
}
