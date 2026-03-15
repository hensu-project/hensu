package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.result.BacktrackEvent;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.node.StandardNode;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/// Handles human review checkpoints for nodes with review configuration.
///
/// Invokes the {@link ReviewHandler} when a {@link StandardNode} has a non-null
/// {@link ReviewConfig}, then maps the {@link ReviewDecision} to a pipeline outcome:
/// - {@link ReviewDecision.Approve} — merges context edits, returns empty (continue)
/// - {@link ReviewDecision.Backtrack} — merges context edits, resets position, returns empty
/// - {@link ReviewDecision.Reject} — returns terminal {@link ExecutionResult.Rejected}
///
/// ### Contracts
/// - **Precondition**: `context.result()` is non-null (post-execution pipeline)
/// - **Postcondition**: Returns empty or terminal result
/// - **Side effects**: May merge context edits from reviewer, appends
///   backtrack events to history on Backtrack decisions
///
/// @implNote Receives {@link ReviewHandler} via constructor injection. Stateless
/// beyond the injected handler reference.
///
/// @see ReviewHandler for review callback contract
/// @see ReviewDecision for possible review outcomes
public final class ReviewPostProcessor implements PostNodeExecutionProcessor {

    private static final Logger logger = Logger.getLogger(ReviewPostProcessor.class.getName());

    private final ReviewHandler reviewHandler;

    /// Creates a review processor with the given handler.
    ///
    /// @param reviewHandler handler for human review callbacks, not null
    public ReviewPostProcessor(ReviewHandler reviewHandler) {
        this.reviewHandler = reviewHandler;
    }

    @Override
    public Optional<ExecutionResult> process(ProcessorContext context) {
        var node = context.currentNode();
        if (!(node instanceof StandardNode standardNode)) {
            return Optional.empty();
        }

        ReviewConfig reviewConfig = standardNode.getReviewConfig();
        if (reviewConfig == null) {
            return Optional.empty();
        }

        ReviewDecision decision = requestReview(standardNode, context);

        return switch (decision) {
            case ReviewDecision.Approve approve -> handleApprove(approve, context);
            case ReviewDecision.Backtrack backtrack ->
                    handleBacktrack(backtrack, node.getId(), context);
            case ReviewDecision.Reject reject ->
                    Optional.of(new ExecutionResult.Rejected(reject.getReason(), context.state()));
        };
    }

    private ReviewDecision requestReview(StandardNode node, ProcessorContext context) {
        ReviewConfig config = node.getReviewConfig();

        if (config.getMode() == ReviewMode.DISABLED) {
            return new ReviewDecision.Approve();
        }

        if (config.getMode() == ReviewMode.OPTIONAL
                && context.result().getStatus() == ResultStatus.SUCCESS) {
            return new ReviewDecision.Approve();
        }

        logger.info("Requesting human review for node: " + node.getId());
        return reviewHandler.requestReview(
                node,
                context.result(),
                context.state(),
                context.state().getHistory(),
                config,
                context.workflow());
    }

    private Optional<ExecutionResult> handleApprove(
            ReviewDecision.Approve approve, ProcessorContext context) {
        if (approve.hasContextEdits()) {
            mergeContextEdits(approve.contextEdits(), context.state());
        }
        return Optional.empty();
    }

    private Optional<ExecutionResult> handleBacktrack(
            ReviewDecision.Backtrack backtrack, String fromNodeId, ProcessorContext context) {

        HensuState state = context.state();

        if (backtrack.hasContextEdits()) {
            mergeContextEdits(backtrack.contextEdits(), state);
        }

        String targetStep = backtrack.getTargetStep();
        state.setCurrentNode(targetStep);

        state.getHistory()
                .addBacktrack(
                        BacktrackEvent.builder()
                                .from(fromNodeId)
                                .to(targetStep)
                                .reason(backtrack.getReason())
                                .build());

        return Optional.empty();
    }

    /// Merges reviewer-provided context edits into the current execution state.
    ///
    /// @param edits context variable overrides from the reviewer, not null
    /// @param state current execution state to merge into, not null
    private void mergeContextEdits(Map<String, Object> edits, HensuState state) {
        state.getContext().putAll(edits);
        logger.info("Merged " + edits.size() + " context edits from reviewer");
    }
}
