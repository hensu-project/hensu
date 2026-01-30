package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;

/// Transition rule that activates on successful node execution.
///
/// Returns the target node when the node result status is {@link ResultStatus#SUCCESS}.
/// This is the most common transition type for happy-path workflow progression.
///
/// @param targetNode the node ID to transition to on success, not null
/// @see FailureTransition for the complementary failure case
/// @see TransitionRule for transition evaluation contract
public record SuccessTransition(String targetNode) implements TransitionRule {

    /// Evaluates whether execution succeeded.
    ///
    /// @param state current workflow state (unused), not null
    /// @param result the node execution result, not null
    /// @return target node ID if result is SUCCESS, null otherwise
    @Override
    public String evaluate(HensuState state, NodeResult result) {
        if (result.getStatus() == ResultStatus.SUCCESS) {
            return targetNode;
        }
        return null;
    }

    /// Returns the transition target node ID.
    ///
    /// @return target node ID, never null
    public String getTargetNode() {
        return targetNode;
    }
}
