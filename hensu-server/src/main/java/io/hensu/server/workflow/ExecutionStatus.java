package io.hensu.server.workflow;

/// Status of an execution.
///
/// @param executionId the execution identifier, never null
/// @param workflowId the workflow definition identifier, never null
/// @param status `COMPLETED` or `PAUSED`, never null
/// @param currentNodeId the node where execution is positioned, may be null if completed
/// @param hasPendingPlan true if a plan is awaiting review
public record ExecutionStatus(
        String executionId,
        String workflowId,
        String status,
        String currentNodeId,
        boolean hasPendingPlan) {}
