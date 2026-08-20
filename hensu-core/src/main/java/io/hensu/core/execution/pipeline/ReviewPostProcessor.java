package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.result.BacktrackEvent;
import io.hensu.core.execution.result.BacktrackType;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.resume.ResumeInput;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.review.ReviewOutcome;
import io.hensu.core.review.ReviewVerdict;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuState;
import io.hensu.core.util.LogSanitizer;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.ApprovalArms;
import java.util.Map;
import java.util.logging.Logger;

/// Handles human review checkpoints for nodes with review configuration.
///
/// Invokes the {@link ReviewHandler} when a {@link StandardNode} has a non-null
/// {@link ReviewConfig}, then maps the {@link ReviewDecision} to a pipeline outcome:
///
/// | Decision | Verdict recorded | Outcome |
/// |---|---|---|
/// | {@link ReviewDecision.Approve} | approved, no reason | CONTINUE — the graph routes |
/// | {@link ReviewDecision.Backtrack} | rejected, with reason | CONTINUE — position reset |
/// | {@link ReviewDecision.Reject}, `onRejection` arm declared | rejected, with reason | CONTINUE —
///                                                                               the graph routes |
/// | {@link ReviewDecision.Reject}, no such arm | none | Terminal(Rejected) |
///
/// ### The reviewer's decision travels on its own channel
/// The verdict is recorded as a {@link ReviewVerdict} on the state, never as an engine
/// variable. The `approved` engine variable says what the *agent* concluded about its own
/// output — {@link OutputExtractionPostProcessor} writes it — and this processor leaves it
/// untouched. Authority is expressed where the decision is read instead:
/// {@link io.hensu.core.workflow.transition.ApprovalTransition} routes on the verdict whenever
/// one is present and falls back to the agent's `approved` otherwise. A human approval
/// therefore overrides an agent's self-rejection without either value overwriting the other,
/// and without the outcome depending on the order of the processor pipeline.
///
/// {@link TransitionPostProcessor} owns the rest of the verdict's lifecycle: it promotes the
/// reviewer's reason to {@link io.hensu.core.execution.EngineVariables#RECOMMENDATION} when the
/// matched transition carries feedback, and clears the verdict once routing is resolved.
///
/// The automatic approvals implied by {@link ReviewMode#DISABLED} and by
/// {@link ReviewMode#OPTIONAL} on a successful result are engine defaults rather than reviewer
/// verdicts, so they record no verdict at all and the graph routes on agent output as usual.
///
/// ### Contracts
/// - **Precondition**: `context.result()` is non-null (post-execution pipeline)
/// - **Postcondition**: Returns CONTINUE or Terminal
/// - **Side effects**: May merge context edits from the reviewer, sets the state's
///   {@link ReviewVerdict}, appends backtrack events to history on Backtrack decisions
///
/// @implNote Receives {@link ReviewHandler} via constructor injection. Stateless
/// beyond the injected handler reference.
///
/// @see ReviewHandler for review callback contract
/// @see ReviewDecision for possible review outcomes
/// @see ReviewVerdict for the verdict channel and its transient lifetime
public final class ReviewPostProcessor implements PostNodeExecutionProcessor {

    public static final String PROCESSOR_ID = "ReviewPostProcessor";

    private static final Logger logger = Logger.getLogger(ReviewPostProcessor.class.getName());

    @Override
    public String id() {
        return PROCESSOR_ID;
    }

    private final ReviewHandler reviewHandler;

    /// Creates a review processor with the given handler.
    ///
    /// @param reviewHandler handler for human review callbacks, not null
    public ReviewPostProcessor(ReviewHandler reviewHandler) {
        this.reviewHandler = reviewHandler;
    }

    @Override
    public ProcessorOutcome process(ProcessorContext context) {
        var node = context.currentNode();
        if (!(node instanceof StandardNode standardNode)) {
            return ProcessorOutcome.CONTINUE;
        }

        ReviewConfig reviewConfig = standardNode.getReviewConfig();
        if (reviewConfig == null) {
            return ProcessorOutcome.CONTINUE;
        }

        HensuState state = context.state();

        // Skip review when a prior processor (e.g. Rubric) already backtracked
        if (state.isNodeRedirected()) {
            return ProcessorOutcome.CONTINUE;
        }
        if (state.getResumeInput() instanceof ResumeInput.ApplyContextEdits) {
            throw new IllegalStateException(
                    "ApplyContextEdits must be handled before reaching the core pipeline");
        }

        if (state.getResumeInput() instanceof ResumeInput.ApplyReview applyReview) {
            ExecutionPhase.validateCorrelation(state.getPhase(), applyReview);
            state.setResumeInput(null);
            logger.info("Applying delivered review decision for node: " + node.getId());
            return handleDecision(applyReview.decision(), standardNode, context);
        }

        if (isAutoApproved(reviewConfig, context)) {
            return ProcessorOutcome.CONTINUE;
        }

        ReviewOutcome outcome = requestReview(standardNode, context);

        return switch (outcome) {
            case ReviewOutcome.Decided(var decision) ->
                    handleDecision(decision, standardNode, context);
            case ReviewOutcome.Pending(var correlationId) ->
                    new ProcessorOutcome.SuspendForExternal(
                            "ReviewPostProcessor", context.result(), correlationId);
        };
    }

    /// Reports whether the engine approves this node without consulting a reviewer.
    ///
    /// These are configuration defaults rather than human verdicts, so the caller returns
    /// CONTINUE without recording a verdict — claiming a reviewer approved a node whose review
    /// is disabled or optional would silently reroute it away from what the agent decided.
    ///
    /// @param config the node's review configuration, not null
    /// @param context the pipeline context carrying the node result, not null
    /// @return true when no human review is required for this execution
    private boolean isAutoApproved(ReviewConfig config, ProcessorContext context) {
        return config.getMode() == ReviewMode.DISABLED
                || (config.getMode() == ReviewMode.OPTIONAL
                        && context.result().getStatus() == ResultStatus.SUCCESS);
    }

    private ProcessorOutcome handleDecision(
            ReviewDecision decision, StandardNode node, ProcessorContext context) {
        return switch (decision) {
            case ReviewDecision.Approve approve -> handleApprove(approve, context);
            case ReviewDecision.Backtrack backtrack ->
                    handleBacktrack(backtrack, node.getId(), context);
            case ReviewDecision.Reject reject -> handleReject(reject, node, context);
        };
    }

    private ReviewOutcome requestReview(StandardNode node, ProcessorContext context) {
        ReviewConfig config = node.getReviewConfig();

        logger.info("Requesting human review for node: " + node.getId());
        return reviewHandler.requestReview(
                node,
                context.result(),
                context.state(),
                context.state().getHistory(),
                config,
                context.workflow());
    }

    private ProcessorOutcome handleApprove(
            ReviewDecision.Approve approve, ProcessorContext context) {
        if (approve.hasContextEdits()) {
            mergeContextEdits(approve.contextEdits(), context.state());
        }
        context.state().setReviewVerdict(ReviewVerdict.approval());
        return ProcessorOutcome.CONTINUE;
    }

    /// Applies a rejection, either by routing it through the graph or by aborting execution.
    ///
    /// When the node declares an `onRejection` arm — bare or decorated by
    /// {@link io.hensu.core.workflow.transition.BoundedTransition} — the rejection becomes a
    /// {@link ReviewVerdict} on the state and the pipeline continues, so
    /// {@link TransitionPostProcessor} resolves that arm and the workflow ends wherever the
    /// author routed it. A node with no rejection arm has nowhere to route, so the rejection
    /// terminates the workflow instead.
    ///
    /// @param reject the reviewer's rejection, not null
    /// @param node the node under review, not null
    /// @param context the pipeline context, not null
    /// @return CONTINUE when the graph routes the rejection, Terminal(Rejected) otherwise
    private ProcessorOutcome handleReject(
            ReviewDecision.Reject reject, StandardNode node, ProcessorContext context) {

        logger.info(
                "Rejecting node: "
                        + LogSanitizer.sanitize(node.getId())
                        + " due to: "
                        + LogSanitizer.sanitize(reject.getReason()));

        if (!ApprovalArms.declares(node.getTransitionRules(), false)) {
            return ProcessorOutcome.terminal(
                    new ExecutionResult.Rejected(reject.getReason(), context.state()));
        }

        context.state().setReviewVerdict(ReviewVerdict.rejection(reject.getReason()));
        return ProcessorOutcome.CONTINUE;
    }

    private ProcessorOutcome handleBacktrack(
            ReviewDecision.Backtrack backtrack, String fromNodeId, ProcessorContext context) {

        HensuState state = context.state();

        if (backtrack.hasContextEdits()) {
            mergeContextEdits(backtrack.contextEdits(), state);
        }

        // The reason is feedback for the node being returned to. TransitionPostProcessor
        // promotes it to `recommendation` on the redirected branch, so the reviewer's words
        // reach the retried node through the same single owner as every other feedback path.
        state.setReviewVerdict(ReviewVerdict.rejection(backtrack.getReason()));

        String targetStep = backtrack.getTargetStep();
        state.setCurrentNode(targetStep);
        state.setNodeRedirected(true);

        state.getHistory()
                .addBacktrack(
                        BacktrackEvent.builder()
                                .from(fromNodeId)
                                .to(targetStep)
                                .reason(backtrack.getReason())
                                .type(BacktrackType.MANUAL)
                                .build());

        return ProcessorOutcome.CONTINUE;
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
