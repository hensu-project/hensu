package io.hensu.core.resume;

import io.hensu.core.execution.pipeline.ProcessorOutcome;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.state.ExecutionPhase;
import java.util.Map;

/// Sealed type carrying caller-supplied input into a resumed execution.
///
/// `ResumeInput` is read by post-processors that suspended via
/// {@link ProcessorOutcome.SuspendForExternal}.
/// It is delivered through the {@link io.hensu.core.execution.executor.ExecutionContext}
/// so processors do not need a back-channel to the caller.
///
/// ### Permitted Subtypes
/// - {@link ApplyReview} — the resume call delivers a {@link ReviewDecision}
/// - {@link ApplyContextEdits} — the resume call delivers free-form context edits
/// - {@link None} — no input attached; ordinary resume after server restart, e.g. recovery
///
/// ### Why this lives in `hensu-core`
/// `ExecutionContext` is a core type. Putting `ResumeInput` in `hensu-server`
/// would force core to depend on server — a layering violation. The server
/// REST layer maps its own JSON shape onto this sealed type at the boundary.
///
/// ### Singletons
/// {@link None} has no fields; use {@link #NONE} instead of allocating.
public sealed interface ResumeInput {

    /// Apply a {@link ReviewDecision} produced out of band by a reviewer.
    ///
    /// @param correlationId correlation id echoed back by the caller to match the
    ///                      {@link ExecutionPhase.Awaiting}
    ///                      that issued the suspension, not null
    /// @param decision      the review decision to apply, not null
    record ApplyReview(String correlationId, ReviewDecision decision) implements ResumeInput {}

    /// Apply free-form context edits without going through a review processor.
    ///
    /// @param edits context variable overrides to merge, not null (may be empty)
    record ApplyContextEdits(Map<String, Object> edits) implements ResumeInput {}

    /// No input attached. Used when resume is purely a continuation (e.g. server
    /// restart after a {@link io.hensu.core.execution.result.ResultStatus#PENDING}
    /// pause).
    record None() implements ResumeInput {}

    /// Shared {@link None} instance — preferred over `new None()`.
    ResumeInput NONE = new None();
}
