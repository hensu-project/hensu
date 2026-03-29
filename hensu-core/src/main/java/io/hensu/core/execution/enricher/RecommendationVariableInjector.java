package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.transition.ApprovalTransition;
import io.hensu.core.workflow.transition.ScoreTransition;

/// Injects the `recommendation` string output requirement into the node prompt.
///
/// Applied when the node has a {@link ScoreTransition} or {@link ApprovalTransition} rule,
/// or when executing a consensus branch that requires self-scoring (non-JUDGE_DECIDES
/// strategies). Ensures the agent justifies its judgment with improvement feedback or
/// review reasoning.
///
/// ### Engine variable contract
///
/// `recommendation` is an engine-managed variable alongside `score` and `approved`.
/// Developers do not declare it in `writes()` — the engine infers it from the graph.
/// {@link io.hensu.core.execution.pipeline.OutputExtractionPostProcessor} extracts it
/// automatically when either transition type is present.
///
/// @see EngineVariableInjector
/// @see ScoreVariableInjector
/// @see ApprovalVariableInjector
/// @see io.hensu.core.workflow.transition.ScoreTransition
/// @see io.hensu.core.workflow.transition.ApprovalTransition
public final class RecommendationVariableInjector implements EngineVariableInjector {

    static final String INSTRUCTION =
            """


                    Engine output requirement: your JSON response MUST include the field\
                     "recommendation" with improvement feedback or review reasoning as a\
                     plain string.""";

    @Override
    public String inject(String prompt, Node node, ExecutionContext ctx) {
        boolean needs =
                node.getTransitionRules().stream()
                                .anyMatch(
                                        r ->
                                                r instanceof ScoreTransition
                                                        || r instanceof ApprovalTransition)
                        || (ctx.getBranchConfig() != null
                                && ctx.getBranchConfig().needsSelfScoring());
        return needs ? prompt + INSTRUCTION : prompt;
    }
}
