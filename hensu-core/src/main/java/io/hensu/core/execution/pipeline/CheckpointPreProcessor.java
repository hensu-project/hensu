package io.hensu.core.execution.pipeline;

/// Fires the {@link io.hensu.core.execution.ExecutionListener#onCheckpoint} callback
/// before each node executes.
///
/// This is the first processor in the pre-execution pipeline. It signals to
/// external observers (e.g., {@code WorkflowService}) that the workflow state is
/// fully consistent and safe to persist for crash-recovery purposes.
///
/// ### Contracts
/// - **Precondition**: `context.result()` is {@code null} (pre-execution pipeline)
/// - **Postcondition**: Always returns {@link ProcessorOutcome#CONTINUE}
/// - **Side effects**: Delegates to the registered
/// {@link io.hensu.core.execution.ExecutionListener}
///
/// @implNote Stateless. Safe to reuse across loop iterations.
///
/// @see io.hensu.core.execution.ExecutionListener#onCheckpoint
/// @see NodeStartPreProcessor for the subsequent pre-execution processor
public final class CheckpointPreProcessor implements PreNodeExecutionProcessor {

    public static final String PROCESSOR_ID = "CheckpointPreProcessor";

    @Override
    public String id() {
        return PROCESSOR_ID;
    }

    @Override
    public ProcessorOutcome process(ProcessorContext context) {
        context.executionContext().getListener().onCheckpoint(context.state());
        return ProcessorOutcome.CONTINUE;
    }
}
