package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.result.ExecutionResult;
import java.util.Optional;

/// Fires the {@link io.hensu.core.execution.ExecutionListener#onNodeComplete} callback
/// after a node's output has been extracted and validated.
///
/// This processor runs second in the post-execution pipeline, after
/// {@link OutputExtractionPostProcessor} has stored the node output in the
/// state context, ensuring observers receive a consistent view of the result.
///
/// ### Contracts
/// - **Precondition**: `context.result()` is non-null (post-execution pipeline)
/// - **Postcondition**: Always returns empty (never short-circuits)
/// - **Side effects**: Delegates to the registered
/// {@link io.hensu.core.execution.ExecutionListener}
///
/// ### Pipeline Position
/// ```
/// OutputExtractionPostProcessor    ← output validated and stored in state
/// NodeCompletePostProcessor        ← this processor fires onNodeComplete
/// HistoryPostProcessor             ← step recorded with final output
/// ```
///
/// @implNote Stateless. Safe to reuse across loop iterations.
///
/// @see io.hensu.core.execution.ExecutionListener#onNodeComplete
/// @see OutputExtractionPostProcessor for the preceding post-execution processor
public final class NodeCompletePostProcessor implements PostNodeExecutionProcessor {

    @Override
    public Optional<ExecutionResult> process(ProcessorContext context) {
        context.executionContext()
                .getListener()
                .onNodeComplete(context.currentNode(), context.result());
        return Optional.empty();
    }
}
