package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.transition.ScoreTransition;

/// Injects the `score` numeric output requirement into the node prompt.
///
/// Applied when the node has a {@link ScoreTransition} rule. This includes both
/// rubric nodes (which must always pair with an {@code onScore} transition) and
/// self-scoring nodes without a rubric.
///
/// @see EngineVariableInjector
/// @see io.hensu.core.workflow.transition.ScoreTransition
public final class ScoreVariableInjector implements EngineVariableInjector {

    static final String INSTRUCTION =
            """


                    Engine output requirement: your JSON response MUST include the field\
                     "score" with a numeric value between 0 and 100 (integer or decimal).\
                     Do not use text, only a JSON number.""";

    @Override
    public String inject(String prompt, Node node, ExecutionContext ctx) {
        boolean hasScoreTransition =
                node.getTransitionRules().stream().anyMatch(r -> r instanceof ScoreTransition);
        return hasScoreTransition ? prompt + INSTRUCTION : prompt;
    }
}
