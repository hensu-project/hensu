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
import java.util.Optional;
import java.util.logging.Logger;

/// Handles human review checkpoints for nodes with review configuration.
///
/// Invokes the {@link ReviewHandler} when a {@link StandardNode} has a non-null
/// {@link ReviewConfig}, then maps the {@link ReviewDecision} to a pipeline outcome:
/// - {@link ReviewDecision.Approve} — returns empty (continue)
/// - {@link ReviewDecision.Backtrack} — mutates state and returns empty
/// - {@link ReviewDecision.Reject} — returns terminal {@link ExecutionResult.Rejected}
///
/// ### Contracts
/// - **Precondition**: `context.result()` is non-null (post-execution pipeline)
/// - **Postcondition**: Returns empty or terminal result
/// - **Side effects**: May mutate state (edited state from reviewer), appends
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
            return new ReviewDecision.Approve(null);
        }

        if (config.getMode() == ReviewMode.OPTIONAL
                && context.result().getStatus() == ResultStatus.SUCCESS) {
            return new ReviewDecision.Approve(null);
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
        if (approve.getEditedState() != null) {
            copyStateFields(approve.getEditedState(), context);
        }
        return Optional.empty();
    }

    private Optional<ExecutionResult> handleBacktrack(
            ReviewDecision.Backtrack backtrack, String fromNodeId, ProcessorContext context) {

        HensuState state = context.state();

        if (backtrack.getEditedState() != null) {
            copyStateFields(backtrack.getEditedState(), context);
            state = context.state();
        }

        String targetStep = backtrack.getTargetStep();
        state.setCurrentNode(targetStep);

        if (backtrack.hasEditedPrompt()) {
            String overrideKey = "_prompt_override_" + targetStep;
            state.getContext().put(overrideKey, backtrack.getEditedPrompt());
            logger.info("Stored edited prompt for node: " + targetStep);
        }

        state.getHistory()
                .addBacktrack(
                        BacktrackEvent.builder()
                                .from(fromNodeId)
                                .to(targetStep)
                                .reason(backtrack.getReason())
                                .build());

        return Optional.empty();
    }

    /// Applies edited state fields from a review decision to the current context state.
    ///
    /// @apiNote The review handler may return modified state. Since `ProcessorContext`
    /// holds a reference to the `ExecutionContext` (which holds the `HensuState`),
    /// we copy the relevant mutable fields from the edited state into the current one.
    private void copyStateFields(HensuState editedState, ProcessorContext context) {
        HensuState current = context.state();
        current.getContext().clear();
        current.getContext().putAll(editedState.getContext());
        current.setCurrentNode(editedState.getCurrentNode());
        current.setRubricEvaluation(editedState.getRubricEvaluation());
    }
}
