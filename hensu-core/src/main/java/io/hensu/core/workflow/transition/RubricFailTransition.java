package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.state.HensuState;
import java.util.function.Function;

/// Transition rule that fires when rubric evaluation fails, delegating target
/// resolution to a predicate function.
///
/// @param function     predicate mapping rubric evaluation to target node, not null
/// @param withFeedback when true, recommendation survives this transition
public record RubricFailTransition(
        Function<RubricEvaluation, String> function, boolean withFeedback)
        implements TransitionRule {

    /// Creates a rubric-fail transition without feedback preservation.
    public RubricFailTransition(Function<RubricEvaluation, String> function) {
        this(function, false);
    }

    @Override
    public String evaluate(HensuState state, NodeResult result) {
        return function.apply(state.getRubricEvaluation());
    }
}
