package io.hensu.core.workflow.transition;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuState;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RubricFailTransitionTest {

    @Test
    void shouldThrowWhenStateHasNoRubricEvaluation() {
        var transition =
                new RubricFailTransition(
                        evaluation -> evaluation.isPassed() ? "continue" : "revise");
        var state =
                new HensuState.Builder()
                        .executionId("exec-1")
                        .workflowId("test-workflow")
                        .currentNode("current-node")
                        .build();
        var result = NodeResult.success("output", Map.of());

        assertThatThrownBy(() -> transition.evaluate(state, result))
                .isInstanceOf(NullPointerException.class);
    }
}
