package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.transition.ApprovalTransition;
import io.hensu.core.workflow.transition.ScoreTransition;

/// Injects the `recommendation` string output requirement into the node prompt.
///
/// Applied when the node has a {@link ScoreTransition} or {@link ApprovalTransition} rule.
/// Both transition types imply that the agent is making a judgment — scoring content or
/// approving/rejecting it — and must justify that judgment with improvement feedback or
/// review reasoning. Without this field, downstream prompt variables such as
/// `{recommendation}` resolve to empty strings and the improvement loop breaks.
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
        boolean applies =
                node.getTransitionRules().stream()
                        .anyMatch(
                                r ->
                                        r instanceof ScoreTransition
                                                || r instanceof ApprovalTransition);
        return applies ? prompt + INSTRUCTION : prompt;
    }
}
