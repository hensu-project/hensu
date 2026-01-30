package io.hensu.core.execution.executor;

import io.hensu.core.workflow.node.GenericNode;

/// Interface for handling GenericNode execution.
///
/// Users implement this interface to define custom logic for generic nodes. Handlers are
/// registered with the {@link NodeExecutorRegistry} by executor type and invoked when a GenericNode
/// with matching type is executed.
///
/// Each handler must provide a unique type identifier via {@link #getType()}. When using CDI,
/// handlers are automatically discovered and registered.
///
/// @see GenericNode
/// @see NodeExecutorRegistry#registerGenericHandler(String, GenericNodeHandler)
public interface GenericNodeHandler {

    /// Returns the unique type identifier for this handler. This type is used to match handlers
    /// with GenericNode's executorType.
    ///
    /// @return The handler type (e.g., "validator", "data-transformer")
    String getType();

    /// Execute custom logic for the given GenericNode.
    ///
    /// @param node The GenericNode being executed (contains config, id, etc.)
    /// @param context The execution context (contains state, services, etc.)
    /// @return The result of execution, including output that can be used by subsequent nodes
    /// @throws Exception if execution fails
    NodeResult handle(GenericNode node, ExecutionContext context) throws Exception;
}
