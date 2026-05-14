package io.hensu.core.execution;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.pipeline.ProcessorContext;
import io.hensu.core.execution.pipeline.ProcessorPipeline;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;

/// Owns the per-node lifecycle: phase dispatch, pipeline orchestration, and
/// node execution.
///
/// Separates "what happens at each node" from the graph-traversal loop in
/// {@link WorkflowExecutor}. The executor loop advances the graph pointer;
/// this coordinator decides how to process the current node based on the
/// execution phase.
///
/// ### Lifecycle modes
/// - **{@link ExecutionPhase.Initial}** — full cycle: reset per-node state,
///   pre-pipeline, execute node, post-pipeline.
/// - **{@link ExecutionPhase.Awaiting}** — resume: re-enter the post-pipeline
///   at the suspended processor with the cached node result.
/// - **{@link ExecutionPhase.Terminal}** — fail fast.
/// - **{@link EndNode}** — terminal short-circuit: execute the end node and
///   produce a {@link ExecutionResult.Completed}.
///
/// ### Return contract
/// - {@code null} — node completed normally, the loop should advance to the
///   next node (state's current node has already been updated by the
///   transition post-processor).
/// - Non-null {@link ExecutionResult} — terminal result (completed, paused,
///   rejected, or failure); the loop should return immediately.
///
/// @implNote Stateless. All mutable state lives in {@link HensuState}.
///
/// @see WorkflowExecutor for the graph-traversal loop
/// @see ProcessorPipeline for pre/post processor chains
public final class NodeLifecycleCoordinator {

    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final ProcessorPipeline prePipeline;
    private final ProcessorPipeline postPipeline;

    public NodeLifecycleCoordinator(
            NodeExecutorRegistry nodeExecutorRegistry,
            ProcessorPipeline prePipeline,
            ProcessorPipeline postPipeline) {
        this.nodeExecutorRegistry = nodeExecutorRegistry;
        this.prePipeline = prePipeline;
        this.postPipeline = postPipeline;
    }

    /// Processes a single node according to the current execution phase.
    ///
    /// @param node    the node to process, not null
    /// @param state   current workflow state, not null
    /// @param context execution context with services, not null
    /// @return terminal result or null to continue traversal
    /// @throws Exception if node execution fails
    ExecutionResult processNode(Node node, HensuState state, ExecutionContext context)
            throws Exception {

        if (state.getPhase() instanceof ExecutionPhase.Awaiting awaiting) {
            return resumeFromAwaiting(awaiting, node, state, context);
        }
        if (state.getPhase() instanceof ExecutionPhase.Terminal) {
            throw new IllegalStateException(
                    "Cannot resume terminal execution " + state.getExecutionId());
        }

        state.setRubricEvaluation(null);
        state.setNodeRedirected(false);

        if (node instanceof EndNode endNode) {
            return executeEndNode(endNode, context, state);
        }

        return executeFullLifecycle(node, context);
    }

    private ExecutionResult resumeFromAwaiting(
            ExecutionPhase.Awaiting awaiting,
            Node node,
            HensuState state,
            ExecutionContext context) {

        if (!awaiting.nodeId().equals(node.getId())) {
            throw new IllegalStateException(
                    "Phase nodeId '"
                            + awaiting.nodeId()
                            + "' != currentNode '"
                            + node.getId()
                            + "'");
        }

        ProcessorContext resumeCtx = new ProcessorContext(context, node, awaiting.cachedResult());
        ExecutionResult postTerminal =
                postPipeline.executePostFrom(awaiting.processorId(), resumeCtx);
        state.setResumeInput(null);
        return postTerminal;
    }

    private ExecutionResult executeEndNode(
            EndNode endNode, ExecutionContext context, HensuState state) throws Exception {

        NodeExecutor<EndNode> executor = nodeExecutorRegistry.getExecutorOrThrow(EndNode.class);
        executor.execute(endNode, context);
        state.setPhase(ExecutionPhase.TERMINAL);
        return new ExecutionResult.Completed(state, endNode.getExitStatus());
    }

    private ExecutionResult executeFullLifecycle(Node node, ExecutionContext context)
            throws Exception {

        ExecutionResult preTerminal =
                prePipeline.executePre(new ProcessorContext(context, node, null));
        if (preTerminal != null) return preTerminal;

        NodeResult result = executeNode(node, context);

        if (result.getStatus() == ResultStatus.PENDING) {
            context.getState().setCurrentNode(node.getId());
            return new ExecutionResult.Paused(context.getState());
        }

        return postPipeline.executePost(new ProcessorContext(context, node, result));
    }

    private NodeResult executeNode(Node node, ExecutionContext context) throws Exception {
        NodeExecutor<Node> executor = nodeExecutorRegistry.getExecutorFor(node);
        return executor.execute(node, context);
    }
}
