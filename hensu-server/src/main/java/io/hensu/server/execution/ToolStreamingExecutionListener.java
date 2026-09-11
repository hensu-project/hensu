package io.hensu.server.execution;

import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolResultEvent;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import java.util.Objects;

/// Republishes tool audit events onto the execution's SSE stream.
///
/// API clients already subscribe to a stream to follow an execution; without
/// this listener the tool trail exists only in the server log, so a caller
/// driving an unattended workflow cannot see what its agent is running until
/// the run is over.
///
/// The events are the SSE-safe projection of the audit records: argument names
/// without values, outcome status with duration and exit code. The full record,
/// including the arguments, belongs in the durable audit sink rather than on an
/// open HTTP connection.
///
/// @implNote Thread-safe: holds only immutable state and delegates to the
/// thread-safe broadcaster. Parallel branches may fire tool events at once.
///
/// @see ExecutionEvent.ToolInvoked for the request projection
/// @see ExecutionEvent.ToolSettled for the outcome projection
public final class ToolStreamingExecutionListener implements ExecutionListener {

    private final ExecutionEventBroadcaster broadcaster;
    private final String executionId;

    /// Creates a listener publishing to one execution's stream.
    ///
    /// @param broadcaster the SSE broadcaster, not null
    /// @param executionId the execution whose subscribers receive the events, not null
    public ToolStreamingExecutionListener(
            ExecutionEventBroadcaster broadcaster, String executionId) {
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster must not be null");
        this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
    }

    @Override
    public void onToolCall(ToolCallEvent event) {
        broadcaster.publish(executionId, ExecutionEvent.ToolInvoked.now(executionId, event));
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        broadcaster.publish(executionId, ExecutionEvent.ToolSettled.now(executionId, event));
    }
}
