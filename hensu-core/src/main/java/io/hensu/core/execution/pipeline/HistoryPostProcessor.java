package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExecutionStep;
import java.time.Instant;
import java.util.Optional;

/// Appends an {@link ExecutionStep} to the execution history after each node completes.
///
/// ### Contracts
/// - **Precondition**: `context.result()` is non-null (post-execution pipeline)
/// - **Postcondition**: Always returns empty (never short-circuits)
/// - **Side effects**: Appends one step to `context.state().getHistory()`
///
/// @implNote Stateless. Safe to reuse across loop iterations.
///
/// @see ExecutionStep for step structure
/// @see io.hensu.core.execution.result.ExecutionHistory for history storage
public final class HistoryPostProcessor implements PostNodeExecutionProcessor {

    @Override
    public Optional<ExecutionResult> process(ProcessorContext context) {
        context.state()
                .getHistory()
                .addStep(
                        new ExecutionStep(
                                context.currentNode().getId(),
                                context.state().snapshot(),
                                context.result(),
                                Instant.now()));

        return Optional.empty();
    }
}
