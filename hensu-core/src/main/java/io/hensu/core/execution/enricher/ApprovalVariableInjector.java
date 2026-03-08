package io.hensu.core.execution.enricher;

/// Injects the `approved` boolean output requirement into the node prompt.
///
/// Appended when a node declares `writes("approved")`. Instructs the agent to include
/// `"approved": true` or `"approved": false` as a JSON boolean in its response.
/// The engine does not interpret free-form text as approval intent — the agent must
/// output a strict boolean so {@link io.hensu.core.workflow.transition.ApprovalTransition}
/// can route without ambiguity.
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
    public String variableName() {
        return "approved";
    }

    @Override
    public String inject(String prompt) {
        return prompt + INSTRUCTION;
    }
}
