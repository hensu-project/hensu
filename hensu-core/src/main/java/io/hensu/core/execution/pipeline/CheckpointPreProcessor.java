package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.result.ExecutionResult;
import java.util.Optional;

/// Fires the {@link io.hensu.core.execution.ExecutionListener#onCheckpoint} callback
/// before each node executes.
///
/// This is the first processor in the pre-execution pipeline. It signals to
/// external observers (e.g., {@code WorkflowService}) that the workflow state is
/// fully consistent and safe to persist for crash-recovery purposes.
///
/// ### Contracts
/// - **Precondition**: `context.result()` is {@code null} (pre-execution pipeline)
/// - **Postcondition**: Always returns empty (never short-circuits)
/// - **Side effects**: Delegates to the registered
/// {@link io.hensu.core.execution.ExecutionListener}
///
/// @implNote Stateless. Safe to reuse across loop iterations.
///
/// @see io.hensu.core.execution.ExecutionListener#onCheckpoint
/// @see NodeStartPreProcessor for the subsequent pre-execution processor
public final class CheckpointPreProcessor implements PreNodeExecutionProcessor {

    @Override
    public Optional<ExecutionResult> process(ProcessorContext context) {
        context.executionContext().getListener().onCheckpoint(context.state());
        return Optional.empty();
    }
}
