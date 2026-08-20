package io.hensu.core.review;

/// A human reviewer's decision, carried on the execution state so the graph can route on it.
///
/// The verdict is a channel of its own rather than an engine variable. Engine variables such as
/// `approved` describe what the *agent* said about its own output; a verdict describes what a
/// *person* decided about that output. Keeping the two apart means
/// {@link io.hensu.core.workflow.transition.ApprovalTransition} can state the precedence rule
/// explicitly — the verdict wins when one is present — instead of relying on which processor
/// happened to write the shared variable last.
///
/// ### Lifetime
/// A verdict is transient. It is produced by
/// {@link io.hensu.core.execution.pipeline.ReviewPostProcessor} when a review decision is applied,
/// and consumed by {@link io.hensu.core.execution.pipeline.TransitionPostProcessor} when the
/// outgoing transition is resolved — both within a single pass of the post-execution pipeline. It
/// is therefore never part of {@link io.hensu.core.state.HensuSnapshot} and never crosses a fork
/// branch boundary.
///
/// A crash inside that window loses the verdict. Recovery restores the last checkpoint, which
/// precedes the review, so the review is requested again — the same at-least-once semantics that
/// already applies to every node execution. Persisting the verdict would not remove that window;
/// it would only move it earlier.
///
/// @param approved `true` when the reviewer approved the node's output, `false` on a rejection or
///     a backtrack
/// @param reason the reviewer's justification, may be null or blank; promoted to
///     {@link io.hensu.core.execution.EngineVariables#RECOMMENDATION} by
///     {@link io.hensu.core.execution.pipeline.TransitionPostProcessor} when the matched
///     transition carries feedback
/// @see ReviewDecision for the full decision surface a reviewer submits
/// @see io.hensu.core.state.HensuState#getReviewVerdict()
public record ReviewVerdict(boolean approved, String reason) {

    /// Creates an approval verdict with no reason attached.
    ///
    /// @return a verdict routing to the `onApproval` arm, never null
    public static ReviewVerdict approval() {
        return new ReviewVerdict(true, null);
    }

    /// Creates a rejection verdict carrying the reviewer's justification.
    ///
    /// @param reason the reviewer's justification, may be null or blank
    /// @return a verdict routing to the `onRejection` arm, never null
    public static ReviewVerdict rejection(String reason) {
        return new ReviewVerdict(false, reason);
    }
}
