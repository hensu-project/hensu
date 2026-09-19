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
/// The two declaration flags are orthogonal and are read together with the run's
/// own mode: `approvalRequired` asks for a human on every call, `unattendedSafe`
/// says whether the call may proceed when there is no human at all. A source
/// that declares neither runs in both modes.
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
/// @param approvalRequired whether the tool's own declaration demands a reviewer on every call
/// @param blocked the outcome that already stands in the way of this call, or null when the
///     call is ready to run — a provider reports {@link ToolCallStatus#SANDBOX_UNAVAILABLE}
///     here so the decorator can demote the call to approval instead of letting it fail
/// @see PreviewCapable for the capability that produces this
public record ToolPreview(
        String summary,
        List<String> argv,
        String sandboxSummary,
        boolean unattendedSafe,
        boolean approvalRequired,
        ToolCallStatus blocked) {

    /// Compact constructor taking defensive copies and rejecting missing text.
    public ToolPreview {
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(sandboxSummary, "sandboxSummary must not be null");
        argv = argv != null ? List.copyOf(argv) : List.of();
    }

    /// Creates a preview of a call that is ready to run.
    ///
    /// @param summary one line naming what would happen, not null
    /// @param argv the resolved argv, may be null or empty
    /// @param sandboxSummary the containment that would apply, not null
    /// @param unattendedSafe whether the source permits running with no human present
    /// @param approvalRequired whether the source demands a reviewer on every call
    public ToolPreview(
            String summary,
            List<String> argv,
            String sandboxSummary,
            boolean unattendedSafe,
            boolean approvalRequired) {
        this(summary, argv, sandboxSummary, unattendedSafe, approvalRequired, null);
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
        return new ToolPreview(summary, List.of(), "", unattendedSafe, false, null);
    }
}
