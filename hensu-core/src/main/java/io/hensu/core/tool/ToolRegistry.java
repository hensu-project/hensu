package io.hensu.core.tool;

import java.util.List;
import java.util.Optional;

/// Registry of available tools for plan generation and execution.
///
/// Tool registries manage the set of tools available during workflow execution.
/// The core module provides the interface; implementations may:
/// - Discover tools from MCP servers (hensu-server)
/// - Load tools from configuration files
/// - Register tools programmatically
///
/// ### Thread Safety
/// @implNote Implementations should be thread-safe for concurrent access
/// during workflow execution.
///
/// ### Multi-Tenancy
/// The `forTenant` method supports per-tenant tool isolation in multi-tenant
/// deployments. Single-tenant deployments can use `all()` directly.
///
/// ### Usage
/// {@snippet :
/// ToolRegistry registry = new DefaultToolRegistry()
///
/// // Register a tool
/// registry.register(ToolDefinition.simple("search", "Search the web"));
///
/// // Retrieve a tool by name
/// Optional<ToolDefinition> tool = registry.get("search");
///
/// // Get all tools for plan generation
/// List<ToolDefinition> available = registry.all();
/// }
///
/// @see ToolDefinition for tool descriptors
/// @see io.hensu.core.plan.Planner for plan generation using tools
public interface ToolRegistry {

    /// Registers a tool definition.
    ///
    /// If a tool with the same name already exists, it will be replaced.
    ///
    /// @apiNote **Side effects**: Modifies internal tool registry
    ///
    /// @param tool the tool definition to register, not null
    /// @throws NullPointerException if tool is null
    void register(ToolDefinition tool);

    /// Retrieves a tool by name.
    ///
    /// @param name the tool identifier to look up, not null
    /// @return the tool definition if found, empty otherwise
    /// @throws NullPointerException if name is null
    Optional<ToolDefinition> get(String name);

    /// Returns all registered tools.
    ///
    /// @return unmodifiable list of all tools, never null (may be empty)
    List<ToolDefinition> all();

    /// Returns tools available for a specific tenant.
    ///
    /// In single-tenant deployments, this typically returns `all()`.
    /// In multi-tenant deployments, returns tenant-scoped tools.
    ///
    /// @param tenantId the tenant identifier, not null
    /// @return tools available for the tenant, never null (may be empty)
    /// @throws NullPointerException if tenantId is null
    default List<ToolDefinition> forTenant(String tenantId) {
        return all();
    }

    /// Returns whether a tool with the given name is registered.
    ///
    /// @param name the tool identifier to check, not null
    /// @return true if the tool exists
    default boolean contains(String name) {
        return get(name).isPresent();
    }

    /// Removes a tool by name.
    ///
    /// @param name the tool identifier to remove, not null
    /// @return true if the tool was removed, false if not found
    default boolean remove(String name) {
        return false;
    }

    /// Returns the number of registered tools.
    ///
    /// @return count of registered tools
    default int size() {
        return all().size();
    }
}
