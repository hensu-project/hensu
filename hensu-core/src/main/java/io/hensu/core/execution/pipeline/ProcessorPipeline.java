package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuState;
import java.time.Instant;
import java.util.List;

/// Executes a list of {@link NodeExecutionProcessor}s in order, short-circuiting
/// on the first non-Continue outcome.
///
/// ### Pre vs post
/// The pipeline exposes two narrowed entry points so the pre/post asymmetry of the
/// {@link ProcessorOutcome.SuspendForExternal} contract is reflected in the API:
/// - {@link #executePre(ProcessorContext)} — pre-pipeline; suspension is invalid
///   (no node result to cache) and is rejected with an {@link IllegalStateException}.
/// - {@link #executePost(ProcessorContext)} — post-pipeline; suspension is the
///   normal pause path: the pipeline records an
///   {@link ExecutionPhase.Awaiting} on `state` and produces an
///   {@link ExecutionResult.Paused}.
///
/// Both return either a terminal {@link ExecutionResult} (caller should return)
/// or {@code null} (caller should continue the loop).
///
/// ### Contracts
/// - **Precondition**: `processors` list is non-null (may be empty)
/// - **Postcondition**: Returns the first terminal result, or null if all processors pass
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

    /// Builds the pre-execution pipeline of {@link PreNodeExecutionProcessor}s.
    ///
    /// Runs before the node's primary logic is invoked. Processors in this
    /// pipeline receive a {@link ProcessorContext} with a {@code null} result.
    ///
    /// Pipeline order:
    /// 1. Checkpoint — fires {@code listener.onCheckpoint(state)} for crash-recovery persistence
    /// 2. Node start — fires {@code listener.onNodeStart(node)} for observability
    ///
    /// @return configured pre-execution pipeline, never null
    public static ProcessorPipeline preExecution() {
        return new ProcessorPipeline(
                List.of(new CheckpointPreProcessor(), new NodeStartPreProcessor()));
    }

    /// Builds the default post-execution pipeline of {@link PostNodeExecutionProcessor}s.
    ///
    /// Pipeline order matters — each processor may short-circuit the chain:
    /// 1. Output extraction — validates (dangerous chars, Unicode tricks, size) then stores
    ///    node output in state context
    /// 2. Rubric — quality evaluation and auto-backtrack (may redirect or terminate).
    ///    Runs before human review so the reviewer only sees machine-validated output,
    ///    and a human approval cannot be overridden by auto-backtrack.
    /// 3. Review — human review checkpoint (may suspend, redirect, or terminate).
    ///    Skipped when Rubric already backtracked (nodeRedirected flag).
    /// 4. Node complete — fires {@code listener.onNodeComplete(node, result)} for observability.
    ///    Runs after Rubric/Review so rejected nodes are never marked complete.
    /// 5. History — appends execution step to history.
    ///    Runs after Rubric/Review so the snapshot includes scores and review edits.
    /// 6. Transition — resolves next node (sets current node in state)
    ///
    /// @param reviewHandler handler for human review callbacks, not null
    /// @param rubricEngine engine for rubric evaluation, not null
    /// @return configured post-execution pipeline, never null
    public static ProcessorPipeline postExecution(
            ReviewHandler reviewHandler, RubricEngine rubricEngine) {
        return new ProcessorPipeline(
                List.of(
                        new OutputExtractionPostProcessor(),
                        new RubricPostProcessor(rubricEngine),
                        new ReviewPostProcessor(reviewHandler),
                        new NodeCompletePostProcessor(),
                        new HistoryPostProcessor(),
                        new TransitionPostProcessor()));
    }

    /// Runs the pre-pipeline.
    ///
    /// @param context the current execution context, not null
    /// @return terminal {@link ExecutionResult} if a processor short-circuited, or null
    /// to continue the loop
    /// @throws IllegalStateException if any processor returns
    /// {@link ProcessorOutcome.SuspendForExternal} — pre-pipeline suspension is
    /// invalid because no node result has been produced yet
    public ExecutionResult executePre(ProcessorContext context) {
        return switch (runProcessors(context)) {
            case ProcessorOutcome.Continue ignored -> null;
            case ProcessorOutcome.Terminal(ExecutionResult result) -> result;
            case ProcessorOutcome.SuspendForExternal suspend ->
                    throw new IllegalStateException(
                            "Pre-pipeline cannot suspend (node="
                                    + context.currentNode().getId()
                                    + ", processor="
                                    + suspend.processorId()
                                    + ")");
        };
    }

    /// Runs the post-pipeline.
    ///
    /// On {@link ProcessorOutcome.SuspendForExternal} the pipeline records an
    /// {@link ExecutionPhase.Awaiting} on `state` so a later resume can
    /// re-enter the post-pipeline at the same processor without re-running
    /// `executeNode`, then returns {@link ExecutionResult.Paused}. On Continue, the
    /// pipeline resets `state.phase` to {@link ExecutionPhase#INITIAL} so the loop's
    /// next iteration is treated as a fresh node.
    ///
    /// @param context the current execution context, not null
    /// @return terminal {@link ExecutionResult} if a processor short-circuited or
    /// suspended, or null to continue the loop
    public ExecutionResult executePost(ProcessorContext context) {
        return switch (runProcessors(context)) {
            case ProcessorOutcome.Continue ignored -> {
                context.state().setPhase(ExecutionPhase.INITIAL);
                yield null;
            }
            case ProcessorOutcome.Terminal(ExecutionResult result) -> {
                context.state().setPhase(ExecutionPhase.TERMINAL);
                yield result;
            }
            case ProcessorOutcome.SuspendForExternal(
                            String processorId,
                            NodeResult cached,
                            String correlationId) -> {
                HensuState state = context.state();
                String nodeId = context.currentNode().getId();
                NodeResult cachedResult = cached != null ? cached : context.result();
                state.setCurrentNode(nodeId);
                state.setPhase(
                        new ExecutionPhase.Awaiting(
                                nodeId, processorId, cachedResult, correlationId, Instant.now()));
                yield new ExecutionResult.Paused(state);
            }
        };
    }

    /// Resumes the post-pipeline from the named processor, skipping all
    /// processors that appear before it in the chain.
    ///
    /// Used on resume after a {@link ProcessorOutcome.SuspendForExternal}:
    /// the executor rebuilds the {@link ProcessorContext} with the cached
    /// {@link io.hensu.core.execution.executor.NodeResult} and calls this method
    /// instead of {@link #executePost(ProcessorContext)}.
    ///
    /// @param processorId simple class name of the processor to resume from, not null
    /// @param context     execution context with cached result, not null
    /// @return terminal {@link ExecutionResult} if a processor short-circuited or
    ///         suspended again, or null to continue the loop
    /// @throws IllegalStateException if {@code processorId} does not match any
    ///         processor in this pipeline
    public ExecutionResult executePostFrom(String processorId, ProcessorContext context) {
        return switch (runProcessorsFrom(processorId, context)) {
            case ProcessorOutcome.Continue ignored -> {
                context.state().setPhase(ExecutionPhase.INITIAL);
                yield null;
            }
            case ProcessorOutcome.Terminal(ExecutionResult result) -> {
                context.state().setPhase(ExecutionPhase.TERMINAL);
                yield result;
            }
            case ProcessorOutcome.SuspendForExternal(
                            String pid,
                            NodeResult cached,
                            String correlationId) -> {
                HensuState state = context.state();
                String nodeId = context.currentNode().getId();
                NodeResult cachedResult = cached != null ? cached : context.result();
                state.setCurrentNode(nodeId);
                state.setPhase(
                        new ExecutionPhase.Awaiting(
                                nodeId, pid, cachedResult, correlationId, Instant.now()));
                yield new ExecutionResult.Paused(state);
            }
        };
    }

    private ProcessorOutcome runProcessors(ProcessorContext context) {
        for (var processor : processors) {
            var outcome = processor.process(context);
            if (!(outcome instanceof ProcessorOutcome.Continue)) {
                return outcome;
            }
        }
        return ProcessorOutcome.CONTINUE;
    }

    private ProcessorOutcome runProcessorsFrom(String processorId, ProcessorContext context) {
        boolean found = false;
        for (var processor : processors) {
            if (!found) {
                if (processor.id().equals(processorId)) {
                    found = true;
                } else {
                    continue;
                }
            }
            var outcome = processor.process(context);
            if (!(outcome instanceof ProcessorOutcome.Continue)) {
                return outcome;
            }
        }
        if (!found) {
            throw new IllegalStateException("Processor not found in post-pipeline: " + processorId);
        }
        return ProcessorOutcome.CONTINUE;
    }
}
