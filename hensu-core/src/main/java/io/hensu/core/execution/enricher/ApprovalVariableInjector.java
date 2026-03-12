package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.transition.ApprovalTransition;

/// Injects the `approved` boolean output requirement into the node prompt.
///
/// Applied when the node has an {@link ApprovalTransition} rule. Instructs the agent
/// to include `"approved": true` or `"approved": false` as a JSON boolean in its response.
/// The engine does not interpret free-form text as approval intent — the agent must
/// output a strict boolean so {@link ApprovalTransition} can route without ambiguity.
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
        boolean hasApprovalTransition =
                node.getTransitionRules().stream().anyMatch(r -> r instanceof ApprovalTransition);
        return hasApprovalTransition ? prompt + INSTRUCTION : prompt;
    }
}
