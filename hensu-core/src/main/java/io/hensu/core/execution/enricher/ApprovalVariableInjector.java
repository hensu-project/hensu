package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.transition.ApprovalTransition;

/// Injects the `approved` boolean output requirement into the node prompt.
///
/// Applied when the node has an {@link ApprovalTransition} rule or when executing
/// a consensus branch that requires self-scoring (non-JUDGE_DECIDES strategies).
/// Instructs the agent to include `"approved": true` or `"approved": false` as a
/// JSON boolean in its response.
///
/// @see EngineVariableInjector
/// @see io.hensu.core.workflow.transition.ApprovalTransition
public final class ApprovalVariableInjector implements EngineVariableInjector {

    static final String INSTRUCTION =
            """


                    Engine output requirement: your JSON response MUST include the field\
                     "approved" with a boolean value — `true` if you approve the content,\
                     `false` if you reject it. Do not use text, only a JSON boolean.""";

    @Override
    public String inject(String prompt, Node node, ExecutionContext ctx) {
        boolean needs =
                node.getTransitionRules().stream().anyMatch(r -> r instanceof ApprovalTransition)
                        || (ctx.getBranchConfig() != null
                                && ctx.getBranchConfig().needsSelfScoring());
        return needs ? prompt + INSTRUCTION : prompt;
    }
}
