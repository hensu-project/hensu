package io.hensu.core.execution.enricher;

import io.hensu.core.execution.EngineVariables;

/// Injects the `approved` boolean output requirement into the node prompt.
///
/// Applied when the node has an {@link io.hensu.core.workflow.transition.ApprovalTransition}
/// rule or when executing a consensus branch that requires self-scoring (non-JUDGE_DECIDES
/// strategies). Instructs the agent to include `"approved": true` or `"approved": false` as a
/// JSON boolean in its response.
///
/// @see TransitionVariableInjector
/// @see io.hensu.core.workflow.transition.ApprovalTransition
public final class ApprovalVariableInjector extends TransitionVariableInjector {

    static final String INSTRUCTION =
            """


                    Engine output requirement: your JSON response MUST include the field\
                     "approved" with a boolean value — `true` if you approve the content,\
                     `false` if you reject it. Do not use text, only a JSON boolean.""";

    @Override
    protected String engineVariable() {
        return EngineVariables.APPROVED;
    }

    @Override
    protected String instruction() {
        return INSTRUCTION;
    }
}
