package io.hensu.core.execution.enricher;

import io.hensu.core.execution.EngineVariables;

/// Injects the `recommendation` string output requirement into the node prompt.
///
/// Applied when the node has a {@link io.hensu.core.workflow.transition.ScoreTransition}
/// or {@link io.hensu.core.workflow.transition.ApprovalTransition} rule, or when executing
/// a consensus branch that requires self-scoring (non-JUDGE_DECIDES strategies). Ensures the
/// agent justifies its judgment with improvement feedback or review reasoning.
///
/// ### Engine variable contract
///
/// `recommendation` is an engine-managed variable alongside `score` and `approved`.
/// Developers do not declare it in `writes()` – the engine infers it from the graph.
/// {@link io.hensu.core.execution.pipeline.OutputExtractionPostProcessor} extracts it
/// automatically when either transition type is present.
///
/// @see TransitionVariableInjector
/// @see ScoreVariableInjector
/// @see ApprovalVariableInjector
/// @see io.hensu.core.workflow.transition.ScoreTransition
/// @see io.hensu.core.workflow.transition.ApprovalTransition
public final class RecommendationVariableInjector extends TransitionVariableInjector {

    static final String INSTRUCTION =
            """


                    Engine output requirement: your JSON response MUST include the field\
                     "recommendation" with improvement feedback or review reasoning as a\
                     plain string.""";

    @Override
    protected String engineVariable() {
        return EngineVariables.RECOMMENDATION;
    }

    @Override
    protected String instruction() {
        return INSTRUCTION;
    }
}
