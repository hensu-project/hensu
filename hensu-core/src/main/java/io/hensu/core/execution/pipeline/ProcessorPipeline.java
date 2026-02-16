package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.rubric.RubricEngine;
import java.util.List;
import java.util.Optional;

/// Executes a list of {@link NodeExecutionProcessor}s in order, short-circuiting
/// on the first terminal result.
///
/// ### Contracts
/// - **Precondition**: `processors` list is non-null (may be empty)
/// - **Postcondition**: Returns the first terminal result, or empty if all processors pass
/// - **Invariant**: Processors are invoked in list order; no processor is skipped
///   unless a prior one short-circuits
///
/// @implNote Stateless and thread-safe. The same pipeline instance can be reused
/// across loop iterations. Processor list is copied at construction time.
///
/// @see NodeExecutionProcessor for individual processor contract
public final class ProcessorPipeline {

    private final List<NodeExecutionProcessor> processors;

    /// Creates a pipeline with the given processors.
    ///
    /// @param processors ordered list of processors to execute, not null
    public ProcessorPipeline(List<NodeExecutionProcessor> processors) {
        this.processors = List.copyOf(processors);
    }

    /// Builds the pre-execution pipeline.
    ///
    /// Runs before the node's primary logic is invoked. Processors in this
    /// pipeline receive a {@link ProcessorContext} with a {@code null} result.
    ///
    /// @apiNote Currently an architectural placeholder; returns an empty pipeline.
    /// Add {@link PreNodeExecutionProcessor}s here for input validation,
    /// context injection, or guard logic.
    ///
    /// @return an empty pre-execution pipeline, never null
    public static ProcessorPipeline preExecution() {
        return new ProcessorPipeline(List.of());
    }

    /// Builds the default post-execution pipeline of {@link PostNodeExecutionProcessor}s.
    ///
    /// Pipeline order matters — each processor may short-circuit the chain:
    /// 1. Output extraction — stores node output in state context
    /// 2. History — appends execution step to history
    /// 3. Review — human review checkpoint (may redirect or terminate)
    /// 4. Rubric — quality evaluation and auto-backtrack (may redirect or terminate)
    /// 5. Transition — resolves next node (sets current node in state)
    ///
    /// @param reviewHandler handler for human review callbacks, not null
    /// @param rubricEngine engine for rubric evaluation, not null
    /// @return configured post-execution pipeline, never null
    public static ProcessorPipeline postExecution(
            ReviewHandler reviewHandler, RubricEngine rubricEngine) {
        return new ProcessorPipeline(
                List.of(
                        new OutputExtractionPostProcessor(),
                        new HistoryPostProcessor(),
                        new ReviewPostProcessor(reviewHandler),
                        new RubricPostProcessor(rubricEngine),
                        new TransitionPostProcessor()));
    }

    /// Executes all processors in order, short-circuiting on first terminal result.
    ///
    /// @param context the current execution context, not null
    /// @return terminal result if any processor short-circuits, empty if all pass
    public Optional<ExecutionResult> execute(ProcessorContext context) {
        for (var processor : processors) {
            var result = processor.process(context);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }
}
