package io.hensu.core.execution;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.workflow.node.Node;

/// Listener for workflow execution events.
///
/// Provides hooks for observability, debugging, and logging during workflow execution.
/// All methods have default no-op implementations, allowing listeners to override
/// only the events they care about.
///
/// ### Usage
/// Implement this interface to receive callbacks during workflow execution:
/// - Track agent invocations and responses
/// - Log node execution progress
/// - Collect metrics for monitoring
/// - Debug workflow behavior
///
/// @implNote All methods must be thread-safe if used with parallel node execution.
/// The listener may receive callbacks from multiple threads simultaneously.
///
/// @see WorkflowExecutor#execute(io.hensu.core.workflow.Workflow, java.util.Map, ExecutionListener)
public interface ExecutionListener {

    /// Called before an agent executes a prompt.
    ///
    /// @param nodeId identifier of the node being executed, not null
    /// @param agentId identifier of the agent handling execution, not null
    /// @param prompt the resolved prompt sent to the agent, not null
    default void onAgentStart(String nodeId, String agentId, String prompt) {}

    /// Called after an agent completes execution.
    ///
    /// @param nodeId identifier of the node that was executed, not null
    /// @param agentId identifier of the agent that handled execution, not null
    /// @param response the agent's response containing output and metadata, not null
    default void onAgentComplete(String nodeId, String agentId, AgentResponse response) {}

    /// Called when a node execution starts.
    ///
    /// @param node the node being executed, not null
    default void onNodeStart(Node node) {}

    /// Called when a node execution completes.
    ///
    /// @param node the node that completed execution, not null
    /// @param result the execution result containing status and output, not null
    default void onNodeComplete(Node node, NodeResult result) {}

    /// No-op listener instance that ignores all events.
    ///
    /// Use this when no observability is needed.
    ExecutionListener NOOP = new ExecutionListener() {};
}
