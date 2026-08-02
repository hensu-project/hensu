package io.hensu.core.tool;

import java.util.Objects;

/// Result of executing a tool call, fed back to the agent for the next round.
///
/// @param toolName name of the tool that was invoked, not null
/// @param success whether the invocation succeeded
/// @param output tool output on success, may be null
/// @param error error message on failure, may be null
public record ToolCallResult(String toolName, boolean success, String output, String error) {

    public ToolCallResult {
        Objects.requireNonNull(toolName, "toolName must not be null");
    }

    /// Creates a successful tool call result.
    ///
    /// @param toolName tool that was invoked, not null
    /// @param output tool output text, not null
    /// @return successful result, never null
    public static ToolCallResult success(String toolName, String output) {
        return new ToolCallResult(toolName, true, output, null);
    }

    /// Creates a failed tool call result.
    ///
    /// @param toolName tool that was invoked, not null
    /// @param error error description, not null
    /// @return failure result, never null
    public static ToolCallResult failure(String toolName, String error) {
        return new ToolCallResult(toolName, false, null, error);
    }

    /// Returns the output on success or "ERROR: " + error on failure.
    ///
    /// @return text representation for feeding back to the agent, never null
    public String asText() {
        return success ? (output != null ? output : "") : "ERROR: " + (error != null ? error : "");
    }
}
