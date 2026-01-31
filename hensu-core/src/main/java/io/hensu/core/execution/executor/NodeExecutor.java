package io.hensu.core.execution.executor;

import io.hensu.core.workflow.node.Node;

/// Strategy interface for executing workflow nodes.
///
/// Each node type ({@link io.hensu.core.workflow.node.StandardNode},
/// {@link io.hensu.core.workflow.node.LoopNode}, {@link io.hensu.core.workflow.node.ParallelNode},
/// etc.) has a corresponding executor implementation that handles its specific execution logic.
///
/// Implementations should be stateless and thread-safe. All execution state is passed via
/// the {@link ExecutionContext}.
///
/// ### Example implementation
/// {@snippet :
/// public class MyCustomNodeExecutor implements NodeExecutor<MyCustomNode> {
///     Class<MyCustomNode> getNodeType(){
///         return MyCustomNode.class;
///     }
///     // Access only what you need from contextAgentRegistry agents = context.getAgentRegistry();
///     // HensuState state = context.getState();
///     // ... execute logic ...return NodeResult.success(output, metadata);
///     NodeResult execute(MyCustomNode node, ExecutionContext context){}
/// }
/// }
///
/// @param <T> The specific node type this executor handles
public interface NodeExecutor<T extends Node> {

    /// Returns the node type this executor handles. Used for type-safe registry lookups.
    ///
    /// @return the Class of the node type
    Class<T> getNodeType();

    /// Execute the given node within the provided execution context.
    ///
    /// ### The ExecutionContext provides access to
    ///
    /// - `state` - Current workflow state and context variables
    /// - `workflow` - The workflow definition
    /// - `listener` - Execution listener for observability
    /// - `agentRegistry` - Registry of available agents
    /// - `templateResolver` - For resolving template variables
    /// - `executorService` - For parallel execution
    /// - `nodeExecutorRegistry` - For delegating to other executors
    /// - `workflowExecutor` - For sub-workflow execution
    ///
    ///
    /// Executors should only access the services they actually need.
    ///
    /// @param node The node to execute
    /// @param context The execution context containing state and services
    /// @return The execution result, never null
    /// @throws Exception if execution fails
    NodeResult execute(T node, ExecutionContext context) throws Exception;
}
