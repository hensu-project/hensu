package io.hensu.core.review;

/// Sealed result of {@link ReviewHandler#requestReview} that allows the handler
/// to choose between answering immediately or deferring the decision to an
/// out-of-band response (e.g. an interactive web UI that posts back later).
///
/// ### Permitted Subtypes
/// - {@link Decided} — handler returns a synchronous {@link ReviewDecision}
///   (Approve/Backtrack/Reject)
/// - {@link Pending} — handler accepted the request and the workflow must pause until a
///   resume call delivers the decision; the `correlationId` ties the resume call back to
///   the suspended execution
///
/// ### Why this exists
/// The previous contract forced handlers to return a `ReviewDecision` synchronously.
/// Production review handlers (web UI, IDE plugin, ticketing) cannot honor that
/// contract — the user is not on the call stack. `ReviewOutcome.Pending` lets
/// the post-pipeline emit a
/// {@link  io.hensu.core.execution.pipeline.ProcessorOutcome.SuspendForExternal}
/// which the executor records as a phase and returns
/// {@link io.hensu.core.execution.result.ExecutionResult.Paused} to its caller.
///
/// @see ReviewHandler
/// @see ReviewDecision
public sealed interface ReviewOutcome {

    /// The handler answered synchronously with `decision`.
    ///
    /// @param decision the decision to apply, not null
    record Decided(ReviewDecision decision) implements ReviewOutcome {}

    /// The handler accepted the request and the answer will arrive out of band.
    /// The execution must pause; on resume, the supplied `correlationId` must
    /// match the originating request.
    ///
    /// @param correlationId opaque external id, not null
    record Pending(String correlationId) implements ReviewOutcome {}

    /// Factory for a synchronous decision outcome.
    ///
    /// @param decision the review decision, not null
    /// @return a {@link Decided} wrapping the decision, never null
    static ReviewOutcome decided(ReviewDecision decision) {
        return new Decided(decision);
    }

    /// Factory for a pending (async) review outcome.
    ///
    /// @param correlationId opaque correlation identifier, not null
    /// @return a {@link Pending} carrying the correlation id, never null
    static ReviewOutcome pending(String correlationId) {
        return new Pending(correlationId);
    }
}
