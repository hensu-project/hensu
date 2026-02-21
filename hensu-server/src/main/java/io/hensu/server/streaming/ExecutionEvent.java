package io.hensu.server.streaming;

import io.hensu.core.plan.PlanEvent;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.plan.StepResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/// SSE event types for execution streaming.
///
/// These DTOs are JSON-serialized and sent to SSE clients.
/// Each event type maps to a corresponding {@link PlanEvent}.
///
/// ### Event Types
/// - `execution.started` - Execution began
/// - `plan.created` - Plan was created (static or dynamic)
/// - `step.started` - Plan step began execution
/// - `step.completed` - Plan step finished
/// - `plan.revised` - Plan was modified after failure
/// - `plan.completed` - Plan execution finished
/// - `execution.completed` - Entire execution finished
/// - `execution.paused` - Execution paused for review
/// - `execution.error` - Error occurred
///
/// @see ExecutionEventBroadcaster for event publishing
/// @see io.hensu.server.api.ExecutionEventResource for SSE endpoint
public sealed interface ExecutionEvent {

    /// Returns the event type identifier.
    ///
    /// @return event type string for SSE event field
    String type();

    /// Returns the execution ID this event belongs to.
    ///
    /// @return execution identifier, never null
    String executionId();

    /// Returns when the event occurred.
    ///
    /// @return event timestamp, never null
    Instant timestamp();

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

    /// Plan created event.
    record PlanCreated(
            String executionId,
            String planId,
            String nodeId,
            String source,
            List<StepInfo> steps,
            Instant timestamp)
            implements ExecutionEvent {

        @Override
        public String type() {
            return "plan.created";
        }

        public static PlanCreated from(String executionId, PlanEvent.PlanCreated event) {
            List<StepInfo> steps = event.steps().stream().map(StepInfo::from).toList();
            return new PlanCreated(
                    executionId,
                    event.planId(),
                    event.nodeId(),
                    event.source().name(),
                    steps,
                    event.timestamp());
        }
    }

    /// Step started event.
    record StepStarted(
            String executionId,
            String planId,
            int stepIndex,
            String toolName,
            String description,
            Instant timestamp)
            implements ExecutionEvent {

        @Override
        public String type() {
            return "step.started";
        }

        public static StepStarted from(String executionId, PlanEvent.StepStarted event) {
            return new StepStarted(
                    executionId,
                    event.planId(),
                    event.step().index(),
                    event.step().toolName(),
                    event.step().description(),
                    event.timestamp());
        }
    }

    /// Step completed event.
    record StepCompleted(
            String executionId,
            String planId,
            int stepIndex,
            boolean success,
            String output,
            String error,
            Instant timestamp)
            implements ExecutionEvent {

        @Override
        public String type() {
            return "step.completed";
        }

        public static StepCompleted from(String executionId, PlanEvent.StepCompleted event) {
            StepResult result = event.result();
            return new StepCompleted(
                    executionId,
                    event.planId(),
                    result.stepIndex(),
                    result.success(),
                    result.output() != null ? result.output().toString() : null,
                    result.error(),
                    event.timestamp());
        }
    }

    /// Plan revised event.
    record PlanRevised(
            String executionId,
            String planId,
            String reason,
            int previousStepCount,
            int newStepCount,
            Instant timestamp)
            implements ExecutionEvent {

        @Override
        public String type() {
            return "plan.revised";
        }

        public static PlanRevised from(String executionId, PlanEvent.PlanRevised event) {
            return new PlanRevised(
                    executionId,
                    event.planId(),
                    event.reason(),
                    event.oldSteps().size(),
                    event.newSteps().size(),
                    event.timestamp());
        }
    }

    /// Plan completed event.
    record PlanCompleted(
            String executionId, String planId, boolean success, String output, Instant timestamp)
            implements ExecutionEvent {

        @Override
        public String type() {
            return "plan.completed";
        }

        public static PlanCompleted from(String executionId, PlanEvent.PlanCompleted event) {
            return new PlanCompleted(
                    executionId,
                    event.planId(),
                    event.success(),
                    event.output(),
                    event.timestamp());
        }
    }

    /// Execution paused event (awaiting review).
    record ExecutionPaused(
            String executionId, String nodeId, String planId, String reason, Instant timestamp)
            implements ExecutionEvent {

        @Override
        public String type() {
            return "execution.paused";
        }

        public static ExecutionPaused now(
                String executionId, String nodeId, String planId, String reason) {
            return new ExecutionPaused(executionId, nodeId, planId, reason, Instant.now());
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

    /// Step information DTO.
    record StepInfo(int index, String toolName, String description, Map<String, Object> arguments) {

        public static StepInfo from(PlannedStep step) {
            return new StepInfo(
                    step.index(), step.toolName(), step.description(), step.arguments());
        }
    }
}
