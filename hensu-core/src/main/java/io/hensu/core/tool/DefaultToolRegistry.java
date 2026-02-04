package io.hensu.core.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// Default thread-safe implementation of {@link ToolRegistry}.
///
/// Uses a ConcurrentHashMap for thread-safe tool registration and lookup.
/// Suitable for single-tenant deployments or as a base for multi-tenant
/// implementations.
///
/// ### Thread Safety
/// @implNote Thread-safe. All operations use ConcurrentHashMap for
/// safe concurrent access from multiple workflow execution threads.
///
/// ### Usage
/// {@snippet :
/// ToolRegistry registry = new DefaultToolRegistry();
/// registry.register(ToolDefinition.simple("search", "Search the web"));
/// }
///
/// @see ToolRegistry for the contract
/// @see ToolDefinition for tool descriptors
public final class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    /// Creates an empty tool registry.
    public DefaultToolRegistry() {}

    /// Creates a tool registry with initial tools.
    ///
    /// @param initialTools tools to register, not null
    public DefaultToolRegistry(List<ToolDefinition> initialTools) {
        Objects.requireNonNull(initialTools, "initialTools must not be null");
        initialTools.forEach(this::register);
    }

    @Override
    public void register(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        tools.put(tool.name(), tool);
    }

    @Override
    public Optional<ToolDefinition> get(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public List<ToolDefinition> all() {
        return List.copyOf(tools.values());
    }

    @Override
    public boolean contains(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return tools.containsKey(name);
    }

    @Override
    public boolean remove(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return tools.remove(name) != null;
    }

    @Override
    public int size() {
        return tools.size();
    }
}
