package io.hensu.core.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.review.ReviewVerdict;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Verifies the isolation guarantees {@link HensuState#branch(String)} owes concurrent sub-flows.
@DisplayName("HensuState.branch")
class HensuStateBranchTest {

    @Test
    @DisplayName("a branch carries no review verdict — a verdict belongs to the reviewed node")
    void branchCarriesNoVerdict() {
        HensuState parent = parentState();
        parent.setReviewVerdict(ReviewVerdict.rejection("not good enough"));

        assertThat(parent.branch("worker").getReviewVerdict()).isNull();
    }

    @Test
    @DisplayName("a branch is marked as such so its checkpoints can be suppressed")
    void branchIsMarked() {
        HensuState parent = parentState();

        assertThat(parent.branch("worker").isBranchState()).isTrue();
        assertThat(parent.isBranchState()).isFalse();
    }

    @Test
    @DisplayName("branches append to private histories — the parent and siblings are untouched")
    void branchHistoryIsIsolated() {
        HensuState parent = parentState();
        parent.getHistory().addStep(step("start"));

        HensuState left = parent.branch("left");
        HensuState right = parent.branch("right");
        left.getHistory().addStep(step("left-work"));
        right.getHistory().addStep(step("right-work"));

        assertThat(parent.getHistory().getSteps()).hasSize(1);
        assertThat(left.getHistory().getSteps()).hasSize(2);
        assertThat(right.getHistory().getSteps()).hasSize(2);
    }

    private HensuState parentState() {
        return new HensuState.Builder()
                .executionId("exec-1")
                .workflowId("wf-1")
                .currentNode("fork")
                .context(new HashMap<>(Map.of("topic", "AI")))
                .history(new ExecutionHistory())
                .build();
    }

    private ExecutionStep step(String nodeId) {
        return ExecutionStep.builder()
                .nodeId(nodeId)
                .result(new NodeResult(ResultStatus.SUCCESS, nodeId + " output", Map.of()))
                .build();
    }
}
