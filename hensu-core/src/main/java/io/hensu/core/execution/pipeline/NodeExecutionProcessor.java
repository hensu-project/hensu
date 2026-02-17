package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.result.ExecutionResult;
import java.util.Optional;

/// Single-responsibility processor for one aspect of pre-node or post-node logic.
///
/// Processors are composed into a {@link ProcessorPipeline} and executed in order.
/// Each processor inspects the {@link ProcessorContext}, optionally mutates
/// {@link io.hensu.core.state.HensuState}, and returns an {@link Optional} to
/// control execution flow.
///
/// ### Return Convention
/// - {@code Optional.empty()} — continue to the next processor (or next loop iteration)
/// - {@code Optional.of(result)} — short-circuit the pipeline and terminate execution
///
/// For redirects (backtrack, transition), processors mutate
/// {@code context.state().setCurrentNode(...)} directly and return empty.
///
/// ### Contracts
/// - **Precondition**: `context` is fully populated for the current pipeline phase
/// - **Postcondition**: Returns a non-null Optional
/// - **Invariant**: Processors must not call other processors directly
///
/// @implNote Implementations should be stateless. All mutable data lives in
/// {@link ProcessorContext#state()}. Processors receive dependencies via
/// constructor injection and are reused across loop iterations.
///
/// @see ProcessorPipeline for composition and short-circuit logic
public interface NodeExecutionProcessor {

    /// Processes one aspect of the execution pipeline.
    ///
    /// @param context the current execution context with state and result, not null
    /// @return empty to continue, or a terminal result to short-circuit
    Optional<ExecutionResult> process(ProcessorContext context);
}
