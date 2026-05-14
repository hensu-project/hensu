package io.hensu.core.execution.pipeline;

/// Fires the {@link io.hensu.core.execution.ExecutionListener#onNodeStart} callback
/// before each node executes.
///
/// This is the second processor in the pre-execution pipeline, running immediately
/// after {@link CheckpointPreProcessor}. It signals to observers that a node is
/// about to begin execution.
///
/// ### Contracts
/// - **Precondition**: `context.result()` is {@code null} (pre-execution pipeline)
/// - **Postcondition**: Always returns {@link ProcessorOutcome#CONTINUE}
/// - **Side effects**: Delegates to the registered
/// {@link io.hensu.core.execution.ExecutionListener}
///
/// @implNote Stateless. Safe to reuse across loop iterations.
///
/// @see io.hensu.core.execution.ExecutionListener#onNodeStart
/// @see CheckpointPreProcessor for the preceding pre-execution processor
public final class NodeStartPreProcessor implements PreNodeExecutionProcessor {

    public static final String PROCESSOR_ID = "NodeStartPreProcessor";

    @Override
    public String id() {
        return PROCESSOR_ID;
    }

    @Override
    public ProcessorOutcome process(ProcessorContext context) {
        context.executionContext().getListener().onNodeStart(context.currentNode());
        return ProcessorOutcome.CONTINUE;
    }
}
