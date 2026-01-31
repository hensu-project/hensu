package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.state.HensuState;
import java.util.function.Function;

public record RubricFailTransition(Function<RubricEvaluation, String> function)
        implements TransitionRule {
    @Override
    public String evaluate(HensuState state, NodeResult result) {
        return function.apply(state.getRubricEvaluation());
    }
}
