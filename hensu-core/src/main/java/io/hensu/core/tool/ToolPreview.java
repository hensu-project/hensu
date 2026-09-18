package io.hensu.core.tool;

import java.util.List;
import java.util.Objects;

/// What a tool call would do, described before it does it.
///
/// A preview exists so that a policy decision about a call can be made outside
/// the provider that would run it. Only the provider can resolve a command
/// template into the argv that will actually execute, so it produces the
/// preview; the decorator that gates the call consumes it, and a reviewer sees
/// the same text the process will receive.
///
/// ### Contracts
/// - **Precondition**: `summary` and `sandboxSummary` are human-readable one-liners
/// - **Postcondition**: `argv` reflects the invocation that would run, element for element
/// - **Invariant**: producing a preview has no side effect on the target system
///
/// @param summary one line naming what would happen, not null
/// @param argv the resolved argv, not null (empty when the tool runs no process)
/// @param sandboxSummary the containment that would apply, not null (may be empty)
/// @param unattendedSafe whether the tool's own declaration permits running with no human present
/// @see PreviewCapable for the capability that produces this
public record ToolPreview(
        String summary, List<String> argv, String sandboxSummary, boolean unattendedSafe) {

    /// Compact constructor taking defensive copies and rejecting missing text.
    public ToolPreview {
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(sandboxSummary, "sandboxSummary must not be null");
        argv = argv != null ? List.copyOf(argv) : List.of();
    }

    /// Creates a preview for a tool that runs no process of its own.
    ///
    /// Used by sources that are already running when the call arrives – a live
    /// MCP server, a built-in – where there is no argv to show and the only
    /// honest description is what is being asked of whom.
    ///
    /// @param summary one line naming what would happen, not null
    /// @param unattendedSafe whether the source permits running with no human present
    /// @return preview carrying no argv, never null
    public static ToolPreview describing(String summary, boolean unattendedSafe) {
        return new ToolPreview(summary, List.of(), "", unattendedSafe);
    }
}
