package io.hensu.core.plan;

import io.hensu.core.execution.executor.NodeResult;
import java.util.List;
import java.util.Optional;

/// Executes a list of {@link PlanProcessor}s in order, short-circuiting
/// on the first terminal result.
///
/// ### Contracts
/// - **Precondition**: {@code processors} list is non-null (may be empty)
/// - **Postcondition**: Returns the first terminal result, or empty if all
///   processors pass through
/// - **Invariant**: Processors are invoked in list order; no processor is skipped
///   unless a prior one short-circuits
///
/// @implNote Stateless and thread-safe. The same pipeline instance can be reused
/// across executions. Processor list is copied at construction time.
///
/// @see PlanProcessor for individual processor contract
/// @see PlanContext for the mutable carrier threaded through the pipeline
public final class PlanPipeline {

    private final List<PlanProcessor> processors;

    /// Creates a pipeline with the given processors.
    ///
    /// @param processors ordered list of processors to execute, not null
    public PlanPipeline(List<PlanProcessor> processors) {
        this.processors = List.copyOf(processors);
    }

    /// Executes all processors in order, short-circuiting on first terminal result.
    ///
    /// @param context the mutable plan context threaded through all processors, not null
    /// @return terminal result if any processor short-circuits, empty if all pass
    public Optional<NodeResult> execute(PlanContext context) {
        for (var processor : processors) {
            var result = processor.process(context);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }
}
