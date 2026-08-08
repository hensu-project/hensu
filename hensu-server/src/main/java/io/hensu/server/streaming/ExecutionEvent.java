package io.hensu.server.streaming;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/// SSE event types for execution streaming.
///
/// These DTOs are JSON-serialized and sent to SSE clients.
///
/// ### Event Types
/// - `execution.started` - Execution began
/// - `execution.completed` - Entire execution finished
/// - `execution.paused` - Execution paused for review
/// - `execution.error` - Error occurred
///
/// @see ExecutionEventBroadcaster for event publishing
/// @see io.hensu.server.api.ExecutionEventResource for SSE endpoint
public sealed interface ExecutionEvent {

    /// Returns the event type identifier.
    ///
    /// Included in the JSON payload so clients can discriminate event types
    /// without relying on the SSE {@code event:} field.
    ///
    /// @return event type string, e.g. {@code "execution.started"}
    @JsonProperty("type")
    String type();

    /// Returns the execution ID this event belongs to.
    ///
    /// @return execution identifier, never null
    String executionId();

    /// Execution started event.
    record ExecutionStarted(
            String executionId, String workflowId, String tenantId, Instant timestamp)
            implements ExecutionEvent {

        @Override
        public String type() {
            return "execution.started";
        }

        public static ExecutionStarted now(String executionId, String workflowId, String tenantId) {
            return new ExecutionStarted(executionId, workflowId, tenantId, Instant.now());
        }
    }

    /// Execution paused event (awaiting review or external input).
    ///
    /// Carries the same public context as {@link ExecutionCompleted} so that clients
    /// connected to the SSE stream do not need a follow-up query to display state.
    /// Includes the {@code correlationId} that clients must echo back when resuming
    /// the execution, enabling the server to validate the resume against the
    /// correct pause point.
    ///
    /// @param executionId   the execution identifier, never null
    /// @param workflowId    the workflow definition identifier, never null
    /// @param nodeId        the node where execution paused, may be null
    /// @param correlationId opaque identifier for this pause point – must be sent
    ///                      back in the resume request, may be null
    /// @param reason        why execution paused (e.g. "review"), never null
    /// @param output        public context variables at the point of pause, never null,
    ///                      may be empty
    /// @param timestamp     when the event occurred, never null
    record ExecutionPaused(
            String executionId,
            String workflowId,
            String nodeId,
            String correlationId,
            String reason,
            Map<String, Object> output,
            Instant timestamp)
            implements ExecutionEvent {

        @Override
        public String type() {
            return "execution.paused";
        }

        /// Creates a pause event with workflow output context.
        ///
        /// @param executionId   the execution identifier, not null
        /// @param workflowId    the workflow identifier, not null
        /// @param nodeId        the node where execution paused, may be null
        /// @param correlationId opaque pause-point identifier, may be null
        /// @param reason        why execution paused, not null
        /// @param output        public context variables, not null
        /// @return new event, never null
        public static ExecutionPaused now(
                String executionId,
                String workflowId,
                String nodeId,
                String correlationId,
                String reason,
                Map<String, Object> output) {
            return new ExecutionPaused(
                    executionId, workflowId, nodeId, correlationId, reason, output, Instant.now());
        }
    }

    /// Execution completed event.
    ///
    /// Carries the public workflow output — the final execution context with internal
    /// system keys (prefixed with `_`) excluded. Clients should use this field
    /// rather than polling for status when they are already connected to the SSE stream.
    ///
    /// @param executionId the execution identifier, never null
    /// @param workflowId the workflow definition identifier, never null
    /// @param success true if the workflow reached a success end node
    /// @param finalNodeId the last node the workflow executed, may be null
    /// @param output public context variables produced by the workflow, never null, may be empty
    /// @param timestamp when the event occurred, never null
    record ExecutionCompleted(
            String executionId,
            String workflowId,
            boolean success,
            String finalNodeId,
            Map<String, Object> output,
            Instant timestamp)
            implements ExecutionEvent {

        @Override
        public String type() {
            return "execution.completed";
        }

        /// Creates a success completion event with workflow output.
        ///
        /// @param executionId the execution identifier, not null
        /// @param workflowId the workflow identifier, not null
        /// @param finalNodeId the last executed node, may be null
        /// @param output public context variables, not null
        /// @return new event, never null
        public static ExecutionCompleted success(
                String executionId,
                String workflowId,
                String finalNodeId,
                Map<String, Object> output) {
            return new ExecutionCompleted(
                    executionId, workflowId, true, finalNodeId, output, Instant.now());
        }

        /// Creates a failure completion event with workflow output.
        ///
        /// @param executionId the execution identifier, not null
        /// @param workflowId the workflow identifier, not null
        /// @param finalNodeId the last executed node, may be null
        /// @param output public context variables at the point of failure, not null
        /// @return new event, never null
        public static ExecutionCompleted failure(
                String executionId,
                String workflowId,
                String finalNodeId,
                Map<String, Object> output) {
            return new ExecutionCompleted(
                    executionId, workflowId, false, finalNodeId, output, Instant.now());
        }
    }

    /// Execution error event.
    record ExecutionError(
            String executionId, String errorType, String message, String nodeId, Instant timestamp)
            implements ExecutionEvent {

        @Override
        public String type() {
            return "execution.error";
        }

        public static ExecutionError now(
                String executionId, String errorType, String message, String nodeId) {
            return new ExecutionError(executionId, errorType, message, nodeId, Instant.now());
        }
    }
}
