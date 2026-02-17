package io.hensu.core.execution.executor;

import io.hensu.core.exception.NodeExecutorNotFound;
import io.hensu.core.workflow.node.Node;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// Default implementation of NodeExecutorRegistry.
///
/// Registers all built-in executors and provides type-safe lookup. Custom executors can be
/// registered to extend workflow capabilities.
///
/// All built-in executors are stateless - they obtain services from ExecutionContext at
/// execution time. This makes the registry simple and thread-safe.
///
/// For GenericNode, handlers are registered by executor type (String key) and looked up at
/// execution time by the GenericNodeExecutor.
public class DefaultNodeExecutorRegistry implements NodeExecutorRegistry {

    private final Map<Class<? extends Node>, NodeExecutor<?>> registry = new HashMap<>();
    private final Map<String, GenericNodeHandler> genericHandlers = new ConcurrentHashMap<>();

    /// Creates a registry with all built-in executors pre-registered.
    public DefaultNodeExecutorRegistry() {
        // Register all built-in executors (stateless)
        register(new StandardNodeExecutor());
        register(new LoopNodeExecutor());
        register(new ParallelNodeExecutor());
        register(new SubWorkflowNodeExecutor());
        register(new EndNodeExecutor());
        register(new ActionNodeExecutor());
        register(new GenericNodeExecutor());
        register(new ForkNodeExecutor());
        register(new JoinNodeExecutor());
    }

    @Override
    public <T extends Node> Optional<NodeExecutor<T>> getExecutor(Class<T> nodeType) {
        return Optional.ofNullable((NodeExecutor<T>) registry.get(nodeType));
    }

    @Override
    public <T extends Node> NodeExecutor<T> getExecutorOrThrow(Class<T> nodeType)
            throws NodeExecutorNotFound {
        return getExecutor(nodeType)
                .orElseThrow(
                        () ->
                                new NodeExecutorNotFound(
                                        "No executor registered for node type: "
                                                + nodeType.getSimpleName()));
    }

    @Override
    public <T extends Node> void register(NodeExecutor<T> executor) {
        registry.put(executor.getNodeType(), executor);
    }

    @Override
    public boolean hasExecutor(Class<? extends Node> nodeType) {
        return registry.containsKey(nodeType);
    }

    // === Generic Node Handler API ===

    @Override
    public void registerGenericHandler(String executorType, GenericNodeHandler handler) {
        if (executorType == null || executorType.isBlank()) {
            throw new IllegalArgumentException("executorType cannot be null or blank");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler cannot be null");
        }
        genericHandlers.put(executorType, handler);
    }

    @Override
    public Optional<GenericNodeHandler> getGenericHandler(String executorType) {
        return Optional.ofNullable(genericHandlers.get(executorType));
    }

    @Override
    public GenericNodeHandler getGenericHandlerOrThrow(String executorType)
            throws NodeExecutorNotFound {
        return getGenericHandler(executorType)
                .orElseThrow(
                        () ->
                                new NodeExecutorNotFound(
                                        "No handler registered for generic executor type: "
                                                + executorType));
    }

    @Override
    public boolean hasGenericHandler(String executorType) {
        return genericHandlers.containsKey(executorType);
    }
}
