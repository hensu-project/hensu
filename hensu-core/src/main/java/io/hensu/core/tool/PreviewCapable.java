package io.hensu.core.tool;

import java.util.Map;

/// Capability of a {@link ToolProvider} that can describe a call before making it.
///
/// Policy about a tool call – does it need approval, may it run with nobody
/// watching – is one decision that belongs in one place. Implementing it inside
/// every provider would be the same rule written once per source, so the engine
/// puts it in a decorator instead. What blocked that shape is that an approval
/// is only meaningful when the reviewer sees the fully resolved invocation, and
/// only the provider can resolve it. This interface is the narrow seam that
/// closes the gap: the decorator asks, the provider answers, and the decision
/// stays outside both.
///
/// A provider that does not implement this is treated as never safe to run
/// unattended, because nothing can describe what it would do.
///
/// ### Contracts
/// - **Precondition**: `toolName` is one this provider {@link ToolProvider#provides}
/// - **Postcondition**: the preview describes the call without performing it
///
/// @apiNote **Side effects**: none. A preview must not launch a process, write
///     a file, or contact a remote system. A provider that has to do work to
///     answer – resolving a template, expanding parameters – does that work
///     and may reuse its result, but nothing observable outside the process may
///     change.
/// @see ToolPreview for the description produced
/// @see ToolProvider for the source being described
public interface PreviewCapable {

    /// Describes what invoking a tool with these arguments would do.
    ///
    /// @param toolName the tool identifier to describe, not null
    /// @param arguments arguments the agent supplied, not null (may be empty)
    /// @return the description of the call, never null
    /// @throws IllegalArgumentException if this provider does not offer the named tool
    ToolPreview preview(String toolName, Map<String, Object> arguments);
}
