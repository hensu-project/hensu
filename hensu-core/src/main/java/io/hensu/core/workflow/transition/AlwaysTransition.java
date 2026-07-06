package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;

/// Unconditional transition that fires on every successful node result.
///
/// Used as the explicit else-arm of `onScore { }` / `onCondition { }` blocks: placed
/// after the conditional arms, it catches every value the arms did not cover so the
/// workflow cannot die with "No valid transition" at runtime.
///
/// Only {@link ResultStatus#SUCCESS} results are routed — a failed node falls through
/// to a later {@link FailureTransition} instead of being swallowed by the else-arm.
///
/// @param targetNode node to transition to, not null
/// @param withFeedback when true, recommendation survives this transition
public record AlwaysTransition(String targetNode, boolean withFeedback) implements TransitionRule {

    /// Creates an always transition without feedback preservation.
    public AlwaysTransition(String targetNode) {
        this(targetNode, false);
    }

    public AlwaysTransition {
        if (targetNode == null || targetNode.isBlank()) {
            throw new IllegalArgumentException("targetNode must not be blank");
        }
    }

    /// Routes every successful result to the target node.
    ///
    /// @param state current workflow state (unused), not null
    /// @param result the node execution result, not null
    /// @return target node ID if result is SUCCESS, null otherwise
    @Override
    public String evaluate(HensuState state, NodeResult result) {
        return result.getStatus() == ResultStatus.SUCCESS ? targetNode : null;
    }
}
