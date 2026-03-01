package io.hensu.server.execution;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.node.Node;
import java.util.List;

/// Fans out all execution lifecycle events to an ordered set of delegates.
///
/// Allows composing independent listeners — e.g., a checkpoint listener and a
/// logging listener — without modifying the caller. All delegates are invoked
/// in declaration order; an exception from one delegate does not prevent the
/// remaining delegates from receiving the event.
///
/// ### Usage
/// {@snippet :
/// ExecutionListener composite = new CompositeExecutionListener(
///     checkpointListener,
///     new LoggingExecutionListener()
/// );
/// workflowExecutor.execute(workflow, initialContext, composite);
/// }
///
/// @implNote Thread-safe if all delegates are thread-safe. Delegates are
/// captured at construction and never mutated.
///
/// @see ExecutionListener
/// @see LoggingExecutionListener
public final class CompositeExecutionListener implements ExecutionListener {

    private final ExecutionListener[] delegates;

    /// Creates a composite listener that dispatches to all provided delegates in order.
    ///
    /// @param delegates listeners to notify; must not be null, elements must not be null
    public CompositeExecutionListener(ExecutionListener... delegates) {
        this.delegates = delegates;
    }

    @Override
    public void onAgentStart(String nodeId, String agentId, String prompt) {
        for (ExecutionListener d : delegates) d.onAgentStart(nodeId, agentId, prompt);
    }

    @Override
    public void onAgentComplete(String nodeId, String agentId, AgentResponse response) {
        for (ExecutionListener d : delegates) d.onAgentComplete(nodeId, agentId, response);
    }

    @Override
    public void onNodeStart(Node node) {
        for (ExecutionListener d : delegates) d.onNodeStart(node);
    }

    @Override
    public void onNodeComplete(Node node, NodeResult result) {
        for (ExecutionListener d : delegates) d.onNodeComplete(node, result);
    }

    @Override
    public void onCheckpoint(HensuState state) {
        for (ExecutionListener d : delegates) d.onCheckpoint(state);
    }

    @Override
    public void onPlannerStart(String nodeId, String planningPrompt) {
        for (ExecutionListener d : delegates) d.onPlannerStart(nodeId, planningPrompt);
    }

    @Override
    public void onPlannerComplete(String nodeId, List<PlannedStep> steps) {
        for (ExecutionListener d : delegates) d.onPlannerComplete(nodeId, steps);
    }
}
