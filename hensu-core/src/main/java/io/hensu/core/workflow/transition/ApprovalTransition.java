package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuState;
import java.util.Map;

/// Boolean approval transition that routes based on the `approved` engine variable.
///
/// Reads the `approved` key written by a node via `writes("approved")` and routes to
/// `targetNode` when the value matches `expected`. Returns `null` (fall-through) if the key
/// is absent or the value is not a strict boolean — the engine never guesses intent from
/// free-form text.
///
/// The engine automatically injects a format instruction into the node's prompt when
/// `approved` appears in `writes`, ensuring the agent outputs a JSON boolean.
///
/// ### Usage (DSL)
/// ```kotlin
/// node("review") {
///     agent = "reviewer"
///     writes("score", "approved")
///     onApproval goto "finalize"
///     onRejection goto "improve"
///     onScore { whenScore lessThan 60.0 goto "draft" }
/// }
/// ```
///
/// @param expected   `true` to match approval; `false` to match rejection
/// @param targetNode node to route to when the context value equals `expected`, not null
/// @see TransitionRule for the evaluation contract
/// @see ScoreTransition for numeric score-based routing
/// @see io.hensu.core.workflow.state.WorkflowStateSchema#ENGINE_VARIABLES
///
/// @implNote **Immutable.** Safe for concurrent evaluation in parallel workflow branches.
public record ApprovalTransition(boolean expected, String targetNode) implements TransitionRule {

    @Override
    public String evaluate(HensuState state, NodeResult result) {
        Map<String, Object> context = state.getContext();
        if (context == null) {
            return null;
        }

        Boolean parsed = parseBoolean(context.get("approved"));
        if (parsed == null) {
            return null;
        }

        return parsed == expected ? targetNode : null;
    }

    private Boolean parseBoolean(Object value) {
        return switch (value) {
            case null -> null;
            case Boolean b -> b;
            case String s -> {
                if ("true".equalsIgnoreCase(s)) yield Boolean.TRUE;
                if ("false".equalsIgnoreCase(s)) yield Boolean.FALSE;
                yield null;
            }
            default -> null;
        };
    }
}
