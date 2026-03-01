package io.hensu.core.execution;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.node.Node;
import java.util.List;

/// Listener for workflow execution lifecycle events.
///
/// Provides hooks for observability, crash-recovery checkpointing, and debugging
/// during workflow execution. All methods have default no-op implementations,
/// allowing listeners to override only the events they care about.
///
/// ### Callback Lifecycle
/// Each non-end node in the execution loop triggers callbacks in this order:
///
/// ```
/// onCheckpoint(state)             — state is consistent, safe to persist
/// onNodeStart(node)               — about to execute node
/// onAgentStart(nodeId, ...)       — about to call LLM (simple agent call)
/// onAgentComplete(nodeId, ...)    — LLM returned result
///   — OR, if planning is enabled —
/// onPlannerStart(nodeId, prompt)  — about to call planning LLM (dynamic mode)
/// onPlannerComplete(nodeId, ...)  — planning LLM returned steps
/// onNodeComplete(node, result)    — node execution finished (state NOT yet updated)
/// [state mutations: output → history → review → rubric → transitions]
/// ```
///
/// ### Usage
/// Implement this interface to receive callbacks during workflow execution:
/// - Checkpoint state for crash recovery ({@link #onCheckpoint})
/// - Track agent invocations and responses
/// - Log node execution progress
/// - Collect metrics for monitoring
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

    /// Called before the planning agent executes a micro-planning prompt.
    ///
    /// Fires during dynamic plan creation, before the LLM is invoked.
    ///
    /// @param nodeId         identifier of the node requesting a plan, not null
    /// @param planningPrompt the full prompt sent to the planning agent, not null
    default void onPlannerStart(String nodeId, String planningPrompt) {}

    /// Called after the planning agent returns a plan.
    ///
    /// @param nodeId identifier of the node that requested the plan, not null
    /// @param steps  the planned steps returned by the LLM, not null
    default void onPlannerComplete(String nodeId, List<PlannedStep> steps) {}

    /// Called when workflow state is fully consistent and safe to persist.
    ///
    /// Fires once per loop iteration, after all state mutations from the previous
    /// node (output, history, review, rubric, transitions) are applied, and before
    /// the next node begins execution. Intended for crash-recovery checkpointing.
    ///
    /// @param state the current workflow state with consistent position and context, not null
    default void onCheckpoint(HensuState state) {}

    /// No-op listener instance that ignores all events.
    ///
    /// Use this when no observability is needed.
    ExecutionListener NOOP = new ExecutionListener() {};
}
