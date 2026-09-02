package io.hensu.core.tool;

import java.time.Instant;
import java.util.Objects;

/// Audit record emitted after a tool invocation settles, whatever the outcome.
///
/// Tool output is unbounded in principle (a build log, a file dump), so the
/// record truncates it to {@link #MAX_OUTPUT_CHARS} characters: an audit trail
/// must stay cheap enough to always be on.
///
/// @param nodeId identifier of the node whose agent requested the tool, not null
/// @param agentId identifier of the requesting agent, not null
/// @param toolName the tool that was invoked, not null
/// @param status the invocation outcome, not null
/// @param durationMs wall-clock duration of the invocation in milliseconds
/// @param output tool output, truncated to {@link #MAX_OUTPUT_CHARS}, may be null
/// @param error error description when the outcome is not success, may be null
/// @param exitCode process exit code for tools that run one, may be null
/// @param occurredAt when the invocation settled, not null
/// @see ToolCallEvent for the matching request record
/// @see ToolCallStatus for the outcome vocabulary
/// @see io.hensu.core.execution.ExecutionListener#onToolResult
public record ToolResultEvent(
        String nodeId,
        String agentId,
        String toolName,
        ToolCallStatus status,
        long durationMs,
        String output,
        String error,
        Integer exitCode,
        Instant occurredAt) {

    /// Maximum number of output characters retained; longer output is truncated.
    public static final int MAX_OUTPUT_CHARS = 4096;

    private static final String TRUNCATION_MARKER = "… [truncated]";

    /// Compact constructor truncating oversized output.
    public ToolResultEvent {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (output != null && output.length() > MAX_OUTPUT_CHARS) {
            output = output.substring(0, MAX_OUTPUT_CHARS) + TRUNCATION_MARKER;
        }
    }

    /// Creates an outcome record timestamped now from a settled result.
    ///
    /// @param nodeId identifier of the requesting node, not null
    /// @param agentId identifier of the requesting agent, not null
    /// @param result the settled invocation result, not null
    /// @param durationMs wall-clock duration in milliseconds
    /// @return new event, never null
    public static ToolResultEvent now(
            String nodeId, String agentId, ToolCallResult result, long durationMs) {
        Objects.requireNonNull(result, "result must not be null");
        return new ToolResultEvent(
                nodeId,
                agentId,
                result.toolName(),
                result.status(),
                durationMs,
                result.output(),
                result.error(),
                result.exitCode(),
                Instant.now());
    }

    /// Returns whether the tool ran and reported success.
    ///
    /// @return true if the status is {@link ToolCallStatus#SUCCESS}
    public boolean success() {
        return status == ToolCallStatus.SUCCESS;
    }
}
