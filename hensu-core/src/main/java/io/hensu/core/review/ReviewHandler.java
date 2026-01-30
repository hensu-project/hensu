package io.hensu.core.review;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;

/// Handler for human review checkpoints in workflow execution.
///
/// Implementations provide the user interface for reviewing node execution results
/// and deciding how to proceed. Common implementations include:
/// - CLI interactive review (CLIReviewManager)
/// - Web-based review UI
/// - IDE integration
/// - Programmatic review (for testing)
///
/// ### Contracts
/// - **Precondition**: Node execution must be complete before review
/// - **Postcondition**: Returns a decision; never blocks indefinitely
/// - **Invariant**: Handler does not modify workflow state directly
///
/// @implNote Implementations should be thread-safe if used in parallel workflows.
/// The handler is invoked synchronously during workflow execution.
///
/// @see ReviewDecision for possible review outcomes
/// @see ReviewConfig for review behavior configuration
@FunctionalInterface
public interface ReviewHandler {

    /// Requests a human review for a completed node execution.
    ///
    /// The handler presents the execution result to the reviewer and collects
    /// their decision: approve, backtrack to a previous step, or reject the
    /// workflow entirely.
    ///
    /// @param node     the executed node requiring review, not null
    /// @param result   the execution result to review, not null
    /// @param state    current workflow state for context, not null
    /// @param history  execution history for backtrack selection, not null
    /// @param config   review behavior settings (allowBacktrack, etc.), not null
    /// @param workflow the workflow for retrieving node metadata for editing purposes, not null
    /// @return review decision (Approve, Backtrack, or Reject), never null
    ReviewDecision requestReview(
            Node node,
            NodeResult result,
            HensuState state,
            ExecutionHistory history,
            ReviewConfig config,
            Workflow workflow);

    /// Auto-approve handler for automated pipelines without human review.
    ///
    /// Always returns {@link ReviewDecision.Approve} without user interaction.
    /// Use when reviews are disabled or in test environments.
    ReviewHandler AUTO_APPROVE = (_, _, _, _, _, _) -> new ReviewDecision.Approve(null);
}
