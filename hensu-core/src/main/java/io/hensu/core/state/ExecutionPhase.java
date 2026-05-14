package io.hensu.core.state;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.pipeline.ProcessorOutcome;
import io.hensu.core.resume.ResumeInput;
import java.time.Instant;

/// Sealed type carrying the position of an execution within a single node's
/// lifecycle.
///
/// While {@link HensuState#getCurrentNode()} answers "which node are we on?",
/// `ExecutionPhase` answers the finer question "where inside that node's
/// pre-execute / execute / post-pipeline are we?". This distinction matters
/// for resume: a workflow paused inside the post-pipeline must NOT re-run the
/// pre-pipeline or `executeNode` on resume — that would duplicate side effects
/// (in particular, repeated LLM calls).
///
/// ### Permitted Subtypes
/// - {@link Initial} — top of the loop, run pre-pipeline + executeNode + post-pipeline normally
/// - {@link Awaiting} — paused inside the post-pipeline; resume by re-running
///   only the post-pipeline starting at the named processor with the cached node result
/// - {@link Terminal} — execution finished; further calls should fail fast
///
/// ### Why this exists
/// The {@link io.hensu.core.execution.WorkflowExecutor} loop is node-grained. To
/// preserve "we are partway through node N's post-pipeline at processor P with
/// cached result R" across server restarts, a string `currentNodeId` is not
/// enough — hence this sealed type, persisted alongside the rest of the
/// snapshot.
///
/// @see ProcessorOutcome.SuspendForExternal
public sealed interface ExecutionPhase {

    /// Top-of-loop phase. The executor will run pre-pipeline, execute the
    /// current node, then run the post-pipeline in full.
    record Initial() implements ExecutionPhase {}

    /// Paused inside the post-pipeline of `nodeId` at `processorId`. On resume,
    /// the executor skips pre-pipeline and `executeNode` for this node and
    /// re-enters the post-pipeline at the named processor using the cached
    /// `NodeResult`.
    ///
    /// @param nodeId         id of the node whose post-pipeline is suspended, not null
    /// @param processorId    stable identifier of the post-processor that suspended, not null
    /// @param cachedResult   result produced by `executeNode(nodeId)` before the suspension,
    ///                       not null
    /// @param correlationId  external correlation id (e.g. review request id) to match resume
    ///                       inputs, not null
    /// @param requestedAt    instant the suspension was recorded, not null
    record Awaiting(
            String nodeId,
            String processorId,
            NodeResult cachedResult,
            String correlationId,
            Instant requestedAt)
            implements ExecutionPhase {}

    /// Terminal phase. The execution has completed (successfully or otherwise)
    /// and must not be resumed.
    record Terminal() implements ExecutionPhase {}

    /// Validates that a resume input's correlation id matches the awaiting phase.
    ///
    /// @param phase       current execution phase, not null
    /// @param resumeInput caller-supplied resume input, not null
    /// @throws IllegalArgumentException if the resume input is {@link ResumeInput.ApplyReview}
    ///         but the phase is not {@link Awaiting}, or if the correlation ids do not match
    static void validateCorrelation(ExecutionPhase phase, ResumeInput resumeInput) {
        if (resumeInput instanceof ResumeInput.ApplyReview review) {
            if (!(phase instanceof Awaiting awaiting)) {
                throw new IllegalArgumentException(
                        "Cannot apply review decision: execution is not awaiting review");
            }
            if (!awaiting.correlationId().equals(review.correlationId())) {
                throw new IllegalArgumentException(
                        "Correlation id mismatch: expected '"
                                + awaiting.correlationId()
                                + "', got '"
                                + review.correlationId()
                                + "'");
            }
        }
    }

    /// Singleton instance for {@link Initial}. Equivalent to `new Initial()`.
    ExecutionPhase INITIAL = new Initial();

    /// Singleton instance for {@link Terminal}. Equivalent to `new Terminal()`.
    ExecutionPhase TERMINAL = new Terminal();
}
