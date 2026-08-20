package io.hensu.core.execution.pipeline;

/// Fires the {@link io.hensu.core.execution.ExecutionListener#onCheckpoint} callback
/// before each node executes.
///
/// This is the first processor in the pre-execution pipeline. It signals to
/// external observers (e.g., {@code WorkflowService}) that the workflow state is
/// fully consistent and safe to persist for crash-recovery purposes.
///
/// ### Fork branches are not checkpointed
/// A checkpoint records one cursor, and the branches of a fork have several — each sub-flow
/// walks its own path concurrently. Letting them all write would leave the durable cursor
/// wherever the last branch happened to be, so recovery would resume inside one sub-flow with
/// its siblings never having run, and the join would then fail for want of their results.
///
/// The fork node is therefore the atomic resume boundary: branch states
/// ({@link io.hensu.core.state.HensuState#isBranchState()}) checkpoint nothing, and the parent's
/// last checkpoint before the fork still points at the fork node itself. The merged parent state
/// is checkpointed normally once the join is reached.
///
/// The consequence is at-least-once execution at fork granularity: a crash anywhere inside a
/// fork re-runs **every** sub-flow on recovery, including branches that had already completed,
/// and their agent calls are billed again. Sub-flow node work must be safe to repeat.
///
/// ### Contracts
/// - **Precondition**: `context.result()` is {@code null} (pre-execution pipeline)
/// - **Postcondition**: Always returns {@link ProcessorOutcome#CONTINUE}
/// - **Side effects**: Delegates to the registered
/// {@link io.hensu.core.execution.ExecutionListener}, except on fork branch states
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
        if (context.state().isBranchState()) {
            return ProcessorOutcome.CONTINUE;
        }
        context.executionContext().getListener().onCheckpoint(context.state());
        return ProcessorOutcome.CONTINUE;
    }
}
