package io.hensu.core.tool;

import java.util.List;
import java.util.Optional;

/// Discovery half of the tool seam: which tools an agent may choose from.
///
/// A registry answers "which tools exist"; {@link ToolInvoker} answers "run this
/// one". The interface is read-only on purpose – catalogs are owned by the
/// {@link ToolProvider} instances behind a {@link ToolRouter}, so there is
/// nothing here to mutate. Adding or removing tools means adding or removing a
/// provider.
///
/// ### Thread Safety
/// @implNote Implementations should be thread-safe for concurrent access
/// during workflow execution.
///
/// ### Usage
/// {@snippet :
/// ToolRegistry registry = new ToolRouter(List.of(commandProvider, mcpProvider));
///
/// // Retrieve a tool by name
/// Optional<ToolDefinition> tool = registry.get("search");
///
/// // Get all tools an agent may choose from
/// List<ToolDefinition> available = registry.all();
/// }
///
/// @see ToolDefinition for tool descriptors
/// @see ToolRouter for the provider-backed implementation used by the engine
public interface ToolRegistry {

    /// Retrieves a tool by name.
    ///
    /// @param name the tool identifier to look up, not null
    /// @return the tool definition if found, empty otherwise
    /// @throws NullPointerException if name is null
    Optional<ToolDefinition> get(String name);

    /// Returns all discoverable tools.
    ///
    /// @return unmodifiable list of all tools, never null (may be empty)
    List<ToolDefinition> all();

    /// Returns whether a tool with the given name is discoverable.
    ///
    /// @param name the tool identifier to check, not null
    /// @return true if the tool exists
    default boolean contains(String name) {
        return get(name).isPresent();
    }

    /// Returns the number of discoverable tools.
    ///
    /// @return count of tools
    default int size() {
        return all().size();
    }
}
