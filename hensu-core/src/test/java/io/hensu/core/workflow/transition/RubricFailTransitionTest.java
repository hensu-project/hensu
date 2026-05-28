package io.hensu.core.workflow.transition;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuState;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RubricFailTransitionTest {

    @Test
    void shouldThrowWhenStateHasNoRubricEvaluation() {
        var transition =
                new RubricFailTransition(
                        evaluation -> evaluation.isPassed() ? "continue" : "revise");
        var state =
                new HensuState(
                        new HashMap<>(), "test-workflow", "current-node", new ExecutionHistory());
        var result = NodeResult.success("output", Map.of());

        assertThatThrownBy(() -> transition.evaluate(state, result))
                .isInstanceOf(NullPointerException.class);
    }
}
