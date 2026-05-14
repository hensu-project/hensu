package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.ExecutionPhase;

/// Sealed result returned by every {@link NodeExecutionProcessor}, replacing
/// the previous `Optional<ExecutionResult>` contract.
///
/// ### Permitted Subtypes
/// - {@link Continue} — proceed to the next processor (or next loop iteration)
/// - {@link Terminal} — short-circuit the entire executor with the given
///   {@link ExecutionResult}; identical to the previous `Optional.of(result)` semantics
/// - {@link SuspendForExternal} — the post-pipeline is to pause at this processor
///   pending an out-of-band resume; the executor records the suspension as an
///   {@link ExecutionPhase.Awaiting} phase and
///   returns {@link ExecutionResult.Paused}
///
/// ### Why this exists
/// `Optional<ExecutionResult>` could express "continue" or "terminate", but had no
/// way to say "pause here, resume into this same processor later with the same
/// cached result". Adding a new variant by widening the return type keeps the
/// migration mechanical for every existing processor.
///
/// ### Singletons
/// {@link Continue} has no fields; use {@link #CONTINUE} instead of allocating.
public sealed interface ProcessorOutcome {

    /// Proceed to the next processor or next loop iteration.
    record Continue() implements ProcessorOutcome {}

    /// Short-circuit the executor with `result`.
    ///
    /// @param result terminal execution result, not null
    record Terminal(ExecutionResult result) implements ProcessorOutcome {}

    /// Suspend the post-pipeline at the named processor for an out-of-band
    /// resume. The cached `NodeResult` is preserved so resume does not have
    /// to re-run the node body.
    ///
    /// @param processorId   stable id of the suspending processor, not null
    /// @param cachedResult  the node result to feed back into the processor on resume, not null
    /// @param correlationId external correlation id matching the resume call, not null
    record SuspendForExternal(String processorId, NodeResult cachedResult, String correlationId)
            implements ProcessorOutcome {}

    /// Shared {@link Continue} instance — preferred over `new Continue()` to
    /// avoid per-processor allocations on the hot path.
    ProcessorOutcome CONTINUE = new Continue();

    /// Convenience factory for the common terminal case.
    ///
    /// @param result terminal execution result, not null
    /// @return a {@link Terminal} wrapping `result`
    static ProcessorOutcome terminal(ExecutionResult result) {
        return new Terminal(result);
    }
}
