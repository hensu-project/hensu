package io.hensu.core.execution.enricher;

import io.hensu.core.execution.EngineVariables;

/// Injects the `score` numeric output requirement into the node prompt.
///
/// Applied when the node has a {@link io.hensu.core.workflow.transition.ScoreTransition}
/// rule or when executing a consensus branch that requires self-scoring
/// (non-JUDGE_DECIDES strategies).
///
/// @see TransitionVariableInjector
/// @see io.hensu.core.workflow.transition.ScoreTransition
public final class ScoreVariableInjector extends TransitionVariableInjector {

    static final String INSTRUCTION =
            """


                    Engine output requirement: your JSON response MUST include the field\
                     "score" with a numeric value between 0 and 100 (integer or decimal).\
                     Do not use text, only a JSON number.""";

    @Override
    protected String engineVariable() {
        return EngineVariables.SCORE;
    }

    @Override
    protected String instruction() {
        return INSTRUCTION;
    }
}
