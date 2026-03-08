package io.hensu.core.execution.enricher;

/// Injects the `score` numeric output requirement into the node prompt.
///
/// Appended when a node declares `writes("score")`. Instructs the agent to include
/// a `"score"` field with a numeric value (0–100) in its JSON response so that
/// {@link io.hensu.core.workflow.transition.ScoreTransition} can evaluate it reliably.
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
    public String variableName() {
        return "score";
    }

    @Override
    public String inject(String prompt) {
        return prompt + INSTRUCTION;
    }
}
