package io.hensu.core.tool;

import java.util.List;
import java.util.Map;

/// Source of callable tools contributed by one runtime integration.
///
/// A provider owns both halves of a tool's lifecycle: it publishes the
/// descriptors an agent may choose from ({@link #tools()}) and performs the
/// actual invocation ({@link #call}). Runtimes plug their own sources into the
/// engine by contributing providers – MCP servers on the server, local commands
/// and stdio MCP servers on the CLI – and the engine composes them through a
/// {@link ToolRouter} without knowing which runtime it is running in.
///
/// ### Dynamic catalogs
/// A provider's catalog may change over time, for example when it is scoped to
/// the tenant currently bound to the calling thread. Implementations must return
/// the catalog that is valid *right now* rather than a snapshot captured at
/// construction, and must return an empty list rather than throwing when no
/// catalog is currently available.
///
/// ### Return, do not throw
/// A {@link ToolRouter} treats a provider that throws from {@link #tools()} as
/// absent for that catalog read, so throwing hides tools instead of failing
/// loudly. A provider whose source can fail must catch the failure, log why,
/// and report an empty catalog deliberately.
///
/// ### Bound your own execution
/// The engine runs no watchdog around {@link #call}: a provider that blocks
/// forever blocks the workflow thread, and nothing can cancel a call already in
/// flight. Every provider must impose its own deadline and return
/// {@link ToolCallStatus#TIMEOUT} rather than hang.
///
/// ### Thread Safety
/// @implNote Implementations must be safe for concurrent access: parallel
/// workflow branches invoke tools from multiple Virtual Threads at once.
///
/// @see ToolRouter for composition of several providers
/// @see ToolInvoker for the invocation half of this contract
public interface ToolProvider {

    /// Returns the tools this provider currently exposes.
    ///
    /// @return tool descriptors, never null (may be empty)
    List<ToolDefinition> tools();

    /// Returns the catalog this provider can report without starting anything.
    ///
    /// {@link ToolRouter}'s constructor reads this rather than {@link #tools()},
    /// because building a router must not have side effects. A provider whose
    /// catalog exists only after a launch – a local MCP server started on first
    /// use – overrides this to report what it already has, so wiring the engine
    /// starts no processes and a run that never calls a tool launches nothing.
    ///
    /// The consequence is that a lazy provider contributes nothing to the
    /// construction-time duplicate check. That check is best-effort by design;
    /// the authoritative one runs on every catalog materialization.
    ///
    /// @return the tools already known, never null (may be empty)
    default List<ToolDefinition> settledTools() {
        return tools();
    }

    /// Returns whether this provider can invoke the named tool.
    ///
    /// The default answers from {@link #tools()}, so the predicate cannot drift
    /// from the catalog. Providers with a cheaper membership test – a keyed
    /// registry, a fixed command catalog – should override it.
    ///
    /// @param toolName the tool identifier to check, not null
    /// @return true if {@link #call} would route this name
    default boolean provides(String toolName) {
        return tools().stream().anyMatch(tool -> tool.name().equals(toolName));
    }

    /// Invokes a tool and returns its result.
    ///
    /// Implementations report failures as a {@link ToolCallResult} carrying the
    /// matching {@link ToolCallStatus} so the agent can react to them; throwing
    /// is reserved for programming errors. Implementations must return within
    /// their own deadline rather than block indefinitely.
    ///
    /// @param toolName the tool identifier to invoke, not null
    /// @param arguments arguments supplied by the agent, not null (may be empty)
    /// @param context the workflow state context, not null (may be empty)
    /// @return the invocation result, never null
    ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context);
}
