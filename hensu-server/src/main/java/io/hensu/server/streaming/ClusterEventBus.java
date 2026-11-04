package io.hensu.server.streaming;

/// Carries execution events between nodes, so a client subscribed on any node sees the
/// events of an execution running on another.
///
/// Events stay what they were on a single node: live notifications, dropped when nobody
/// is subscribed, never replayed. The authoritative state is the execution resource.
public interface ClusterEventBus {

    /// Publishes an event to every node's subscribers of the execution, this one included.
    ///
    /// @param executionId the execution, not null
    /// @param event the event, not null
    void publish(String executionId, ExecutionEvent event);

    /// Ends every node's subscriptions to the execution.
    ///
    /// @param executionId the execution, not null
    void complete(String executionId);
}
