package io.hensu.server.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// One settled tool invocation, as the audit trail stores it.
///
/// Refusals are entries too. A denied call that left no row would make "the run never
/// tried" and "the run was stopped" indistinguishable afterwards, which is the one
/// question an audit trail exists to answer.
///
/// ### Contracts
/// - **Invariant**: `arguments` is already redacted — a parameter declared `secret:`
///   arrives carrying {@link io.hensu.core.tool.ToolCallEvent#REDACTED}, never its value
/// - **Invariant**: `argv` is empty for tools that run no process
///
/// @param executionId id of the execution that made the call
/// @param nodeId id of the node whose agent asked
/// @param agentId id of the calling agent
/// @param toolName the tool that was invoked
/// @param status the outcome, as a {@link io.hensu.core.tool.ToolCallStatus} name
/// @param exitCode the process exit code where there was a process, may be null
/// @param durationMs how long the call took
/// @param occurredAt when the call settled
/// @param argv the invocation as it ran, empty when no process was involved
/// @param arguments the redacted arguments the agent supplied
/// @param output what the tool produced, already truncated, may be null
/// @see ToolAuditRepository for where these are written
public record ToolAuditEntry(
        String executionId,
        String nodeId,
        String agentId,
        String toolName,
        String status,
        Integer exitCode,
        long durationMs,
        Instant occurredAt,
        List<String> argv,
        Map<String, Object> arguments,
        String output) {

    /// Compact constructor taking defensive copies and rejecting missing identity.
    public ToolAuditEntry {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        argv = argv != null ? List.copyOf(argv) : List.of();
        arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
    }
}
