package io.hensu.core.workflow.transition;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.review.ReviewVerdict;
import io.hensu.core.state.HensuState;
import java.util.Map;
import java.util.Set;

/// Boolean approval transition that routes on an approval verdict.
///
/// Routes to `targetNode` when the verdict matches `expected`, and returns `null`
/// (fall-through) otherwise. The engine automatically injects a format instruction into the
/// node's prompt so the agent outputs a JSON boolean for `approved`.
///
/// ### Precedence: a human outranks the agent
/// Two parties can express an approval, and they are read in a fixed order:
///
/// 1. A {@link io.hensu.core.review.ReviewVerdict} on the state, set by
///    {@link io.hensu.core.execution.pipeline.ReviewPostProcessor} when a human reviewed this
///    node. When present it decides the route outright.
/// 2. Otherwise, the `approved` engine variable in the state context, which
///    {@link io.hensu.core.execution.pipeline.OutputExtractionPostProcessor} extracts from the
///    agent's own output. A non-boolean or absent value falls through — the engine never
///    guesses intent from free-form text.
///
/// Stating the precedence here rather than letting the two writers share one variable is what
/// makes "the reviewer decides" a property of this rule instead of an accident of processor
/// ordering.
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
/// @param expected       `true` to match approval; `false` to match rejection
/// @param targetNode     node to route to when the context value equals `expected`, not null
/// @param withFeedback   when true, recommendation survives this transition
/// @see TransitionRule for the evaluation contract
/// @see ScoreTransition for numeric score-based routing
/// @see io.hensu.core.workflow.state.WorkflowStateSchema#ENGINE_VARIABLES
///
/// @implNote **Immutable.** Safe for concurrent evaluation in parallel workflow branches.
public record ApprovalTransition(boolean expected, String targetNode, boolean withFeedback)
        implements TransitionRule {

    /// Creates an approval transition without feedback preservation.
    public ApprovalTransition(boolean expected, String targetNode) {
        this(expected, targetNode, false);
    }

    @Override
    public Set<String> requiredRoutingVars() {
        return Set.of(EngineVariables.APPROVED, EngineVariables.RECOMMENDATION);
    }

    @Override
    public String evaluate(HensuState state, NodeResult result) {
        ReviewVerdict verdict = state.getReviewVerdict();
        if (verdict != null) {
            return verdict.approved() == expected ? targetNode : null;
        }

        Map<String, Object> context = state.getContext();
        if (context == null) {
            return null;
        }

        Boolean parsed = parseBoolean(context.get(EngineVariables.APPROVED));
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
