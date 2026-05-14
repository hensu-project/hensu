package io.hensu.core.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HensuSnapshotTest {

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
    void shouldRoundTripAwaitingPhase() {
        var phase =
                new ExecutionPhase.Awaiting(
                        "node-1",
                        "ReviewPostProcessor",
                        NodeResult.success("cached output", Map.of()),
                        "corr-123",
                        Instant.now());

        var state =
                new HensuState.Builder()
                        .workflowId("wf-1")
                        .executionId("exec-1")
                        .currentNode("node-1")
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .phase(phase)
                        .build();

        HensuSnapshot snapshot = HensuSnapshot.from(state, "human-review");
        HensuState restored = snapshot.toState();

        assertThat(restored.getPhase()).isInstanceOf(ExecutionPhase.Awaiting.class);
        var awaiting = (ExecutionPhase.Awaiting) restored.getPhase();
        assertThat(awaiting.nodeId()).isEqualTo("node-1");
        assertThat(awaiting.processorId()).isEqualTo("ReviewPostProcessor");
        assertThat(awaiting.correlationId()).isEqualTo("corr-123");
    }
}
