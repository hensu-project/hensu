package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;

/// Pure consensus-failure trigger – routes when a parallel node fails to reach consensus.
///
/// Fires only when the result status is {@link ResultStatus#FAILURE} and the result metadata
/// contains {@code consensus_reached = false} (set by {@code ParallelNodeExecutor}). This is
/// the consensus-specific counterpart to {@link FailureTransition}, which explicitly excludes
/// consensus failures.
///
/// @param targetNode   node to transition to when consensus fails, not null
/// @param withFeedback when true, recommendation survives this transition
/// @see FailureTransition for agent-execution failures
/// @see BoundedTransition for wrapping with a retry budget
public record NoConsensusTransition(String targetNode, boolean withFeedback)
        implements TransitionRule {

    /// Creates a no-consensus transition without feedback preservation.
    public NoConsensusTransition(String targetNode) {
        this(targetNode, false);
    }

    @Override
    public String evaluate(HensuState state, NodeResult result) {
        if (result.getStatus() != ResultStatus.FAILURE) return null;
        Object consensusReached = result.getMetadata().get("consensus_reached");
        if (!Boolean.FALSE.equals(consensusReached)) return null;
        return targetNode;
    }
}
