package io.hensu.server.execution;

import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolResultEvent;
import io.hensu.server.persistence.ToolAuditEntry;
import io.hensu.server.persistence.ToolAuditRepository;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// Writes one durable row per settled tool call.
///
/// The arguments arrive on the call event and the outcome on the result event, so a row
/// is only complete once both have been seen. The listener holds the pending call until
/// its result lands, keyed by the call id both events carry.
///
/// It is composed **unconditionally**, unlike the logging listener, which a default
/// deployment leaves switched off. A record that a configuration flag can silence is a
/// debugging aid; §7.5 asks for a record.
///
/// ### Contracts
/// - **Postcondition**: every {@link #onToolResult} produces exactly one stored entry
/// - **Invariant**: a result with no preceding call still produces a row, with empty
///   arguments — losing the row would be worse than losing the arguments
///
/// @implNote Thread-safe. Parallel fan-out branches settle calls concurrently. The pending
///     map is keyed by call id rather than by node and tool name because those identify a
///     call only while no two concurrent calls can share them — true of today's graph
///     shapes, but a property of the graph rather than of this listener.
/// @see ToolAuditRepository for where rows go
public final class ToolAuditListener implements ExecutionListener {

    private final ToolAuditRepository repository;
    private final String tenantId;
    private final String executionId;
    private final Map<String, ToolCallEvent> pending = new ConcurrentHashMap<>();

    /// Creates a listener recording one execution's tool calls.
    ///
    /// @param repository where rows are written, not null
    /// @param tenantId the tenant owning the execution, not null
    /// @param executionId the execution being recorded, not null
    public ToolAuditListener(ToolAuditRepository repository, String tenantId, String executionId) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
    }

    @Override
    public void onToolCall(ToolCallEvent event) {
        pending.put(event.callId(), event);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        ToolCallEvent call = pending.remove(event.callId());
        repository.record(
                tenantId,
                new ToolAuditEntry(
                        executionId,
                        event.nodeId(),
                        event.agentId(),
                        event.toolName(),
                        event.status().name(),
                        event.exitCode(),
                        event.durationMs(),
                        event.occurredAt(),
                        event.argv(),
                        call != null ? call.arguments() : Map.of(),
                        event.output()));
    }
}
