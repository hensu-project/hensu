package io.hensu.core.execution.pipeline;

/// Single-responsibility processor for one aspect of pre-node or post-node logic.
///
/// Processors are composed into a {@link ProcessorPipeline} and executed in order.
/// Each processor inspects the {@link ProcessorContext}, optionally mutates
/// {@link io.hensu.core.state.HensuState}, and returns a {@link ProcessorOutcome}
/// to control execution flow.
///
/// ### Return Convention
/// - {@link ProcessorOutcome#CONTINUE} — continue to the next processor (or next loop
///   iteration)
/// - {@link ProcessorOutcome.Terminal} — short-circuit the pipeline and terminate execution
/// - {@link ProcessorOutcome.SuspendForExternal} — pause the post-pipeline at this processor
///   pending an out-of-band resume (post-pipeline only; emitting from the pre-pipeline is invalid)
///
/// For redirects (backtrack, transition), processors mutate
/// {@code context.state().setCurrentNode(...)} directly and return
/// {@link ProcessorOutcome#CONTINUE}.
///
/// ### Contracts
/// - **Precondition**: `context` is fully populated for the current pipeline phase
/// - **Postcondition**: Returns a non-null {@link ProcessorOutcome}
/// - **Invariant**: Processors must not call other processors directly
///
/// @implNote Implementations should be stateless. All mutable data lives in
/// {@link ProcessorContext#state()}. Processors receive dependencies via
/// constructor injection and are reused across loop iterations.
///
/// @see ProcessorPipeline for composition and short-circuit logic
public interface NodeExecutionProcessor {

    /// Stable identifier for this processor, used to persist and restore pipeline
    /// position across pause/resume cycles.
    ///
    /// Implementations must return a compile-time constant so that renaming
    /// the class does not break in-flight resume deserialization.
    ///
    /// @return non-null, unique within a pipeline
    String id();

    /// Processes one aspect of the execution pipeline.
    ///
    /// @param context the current execution context with state and result, not null
    /// @return outcome controlling whether to continue, terminate, or suspend, never null
    ProcessorOutcome process(ProcessorContext context);
}
