package io.hensu.core.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.result.ExecutionHistory;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HensuSnapshotTest {

    @Test
    void shouldRejectNullWorkflowId() {
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
    void shouldMakeDefensiveCopyOfContext() {
        Map<String, Object> originalContext = new HashMap<>();
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

    @Test
    void shouldRestoreStateWithMutableCollections() {
        // Regression: toState() must produce mutable context/history for resumed execution.
        // List.copyOf() / Map.copyOf() broke mutation during resumed workflow runs.
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
        assertThat(restored.getCurrentNode()).isEqualTo("current-node");
        assertThat(restored.getContext()).containsEntry("key", "value");

        // The restored context must be mutable for the executor to write into
        restored.getContext().put("new_key", "new_value");
        assertThat(restored.getContext()).containsEntry("new_key", "new_value");
    }

    @Test
    void shouldTreatNullCurrentNodeAsCompleted() {
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

        assertThat(snapshot.isCompleted()).isTrue();
        assertThat(snapshot.currentNodeId()).isNull();
    }
}
