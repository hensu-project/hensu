package io.hensu.core.execution.executor;

import io.hensu.core.workflow.node.Node;
import java.util.Optional;

/// Registry for node executors.
///
/// Provides type-safe lookup of executors by node class. Custom node executors can be
/// registered to extend workflow capabilities.
///
/// ### Example usage
/// {@snippet :
/// // Type-safe
/// lookupNodeExecutor<StandardNode> executor = registry.getExecutor(StandardNode.class);
/// // Register custom executor
/// registry.register(new MyCustomNodeExecutor());
/// }
public interface NodeExecutorRegistry {

    /// Get executor for the given node type.
    ///
    /// @param nodeType The node class
    /// @param <T> The node type
    /// @return Optional containing the executor if found
    <T extends Node> Optional<NodeExecutor<T>> getExecutor(Class<T> nodeType);

    /// Get executor for the given node type, throwing if not found.
    ///
    /// @param nodeType The node class
    /// @param <T> The node type
    /// @return The executor
    /// @throws NodeExecutorNotFound if no executor is registered
    <T extends Node> NodeExecutor<T> getExecutorOrThrow(Class<T> nodeType)
            throws NodeExecutorNotFound;

    /// Get executor for the given node instance. Convenience method that extracts the node's class.
    ///
    /// @param node The node instance
    /// @param <T> The node type
    /// @return The executor
    /// @throws NodeExecutorNotFound if no executor is registered
    default <T extends Node> NodeExecutor<T> getExecutorFor(T node) throws NodeExecutorNotFound {
        return (NodeExecutor<T>) getExecutorOrThrow(node.getClass());
    }

    /// Register a node executor. The executor's node type is determined by
    /// {@link NodeExecutor#getNodeType()}.
    ///
    /// @param executor The executor to register
    /// @param <T> The node type
    <T extends Node> void register(NodeExecutor<T> executor);

    /// Check if an executor is registered for the given node type.
    ///
    /// @param nodeType The node class
    /// @return true if an executor is registered
    boolean hasExecutor(Class<? extends Node> nodeType);

    // === Generic Node Handler API ===

    /// Register a handler for GenericNode execution by executor type.
    ///
    /// Multiple GenericNodes can share the same handler (same executorType), or each can have a
    /// unique handler.
    ///
    /// ### Example
    /// {@snippet :
    /// registry.registerGenericHandler(
    ///     "validator", (node, ctx) -> NodeResult.success("Valid", Map.of()));
    /// }
    ///
    /// @param executorType The type identifier (e.g., "validator", "transformer")
    /// @param handler The handler implementation
    void registerGenericHandler(String executorType, GenericNodeHandler handler);

    /// Get handler for the given executor type.
    ///
    /// @param executorType The type identifier
    /// @return Optional containing the handler if found
    Optional<GenericNodeHandler> getGenericHandler(String executorType);

    /// Get handler for the given executor type, throwing if not found.
    ///
    /// @param executorType The type identifier
    /// @return The handler
    /// @throws NodeExecutorNotFound if no handler is registered
    GenericNodeHandler getGenericHandlerOrThrow(String executorType) throws NodeExecutorNotFound;

    /// Check if a handler is registered for the given executor type.
    ///
    /// @param executorType The type identifier
    /// @return true if a handler is registered
    boolean hasGenericHandler(String executorType);
}
