package io.hensu.core.execution.pipeline;

import io.hensu.core.state.HensuSnapshot;

/// Records the checkpoint that opens each node attempt.
///
/// This is the first processor in the pre-execution pipeline. The workflow state is
/// fully consistent here, and everything the attempt does – model turns, tool calls –
/// is journaled under the position this checkpoint fixes, so a recovered run re-enters
/// exactly this attempt.
///
/// ### Main flow and nested flows
/// The main flow's checkpoint is the execution's own state: it goes to the
/// {@link io.hensu.core.execution.ExecutionListener#onCheckpoint} listener, which on the
/// server writes the execution row under the run's claim. A nested flow – a fork
/// branch, a sub-workflow – runs concurrently with its siblings, and one execution row
/// cannot hold several cursors, so its checkpoint goes to the
/// {@link io.hensu.core.execution.ExecutionJournal} under the flow's scope instead. A
/// recovery that re-enters the fork node resumes each branch from its own latest
/// checkpoint: a branch that had finished is not run again, and a branch that was mid-node
/// re-enters that node's attempt and replays it from the journal.
///
/// ### Contracts
/// - **Precondition**: `context.result()` is {@code null} (pre-execution pipeline)
/// - **Postcondition**: Always returns {@link ProcessorOutcome#CONTINUE}
/// - **Side effects**: one checkpoint write, to the listener or the journal
///
/// @implNote Stateless. Safe to reuse across loop iterations.
///
/// @see io.hensu.core.execution.ExecutionListener#onCheckpoint
/// @see io.hensu.core.execution.ExecutionJournal#recordFlowCheckpoint
/// @see NodeStartPreProcessor for the subsequent pre-execution processor
public final class CheckpointPreProcessor implements PreNodeExecutionProcessor {

    public static final String PROCESSOR_ID = "CheckpointPreProcessor";

    @Override
    public String id() {
        return PROCESSOR_ID;
    }

    @Override
    public ProcessorOutcome process(ProcessorContext context) {
        if (context.state().isNested()) {
            context.executionContext()
                    .getJournal()
                    .recordFlowCheckpoint(
                            context.state().getScope(),
                            HensuSnapshot.from(context.state(), "checkpoint"));
        } else {
            context.executionContext().getListener().onCheckpoint(context.state());
        }
        return ProcessorOutcome.CONTINUE;
    }
}
