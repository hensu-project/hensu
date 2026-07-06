package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;

/// Pure failure trigger – routes on {@link ResultStatus#FAILURE} without mutating state.
///
/// A {@code null} target means "retry the current node" (self-loop), used by the
/// {@code onFailure retry} DSL desugaring. Counter management and budget enforcement
/// are handled by wrapping this rule in {@link BoundedTransition}.
///
/// Consensus failures ({@code consensus_reached = false} in result metadata) are
/// explicitly excluded – those belong to {@link NoConsensusTransition}.
///
/// @param targetNode node to transition to on failure, or null for self-loop retry
/// @see BoundedTransition for retry budgeting
/// @see NoConsensusTransition for the consensus-failure counterpart
public record FailureTransition(String targetNode) implements TransitionRule {

    @Override
    public String evaluate(HensuState state, NodeResult result) {
        if (result.getStatus() != ResultStatus.FAILURE) return null;

        // B2: consensus failures belong to NoConsensusTransition
        Object consensusReached = result.getMetadata().get("consensus_reached");
        if (Boolean.FALSE.equals(consensusReached)) return null;

        return targetNode != null ? targetNode : state.getCurrentNode();
    }

    /// Execution failures carry no agent-evaluated feedback – the recommendation is cleared
    /// on retry so the next attempt starts from the original prompt.
    @Override
    public RetryFeedback retryFeedback() {
        return RetryFeedback.NONE;
    }
}
