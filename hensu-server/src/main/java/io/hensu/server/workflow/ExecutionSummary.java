package io.hensu.server.workflow;

import java.time.Instant;

/// Summary of an execution.
///
/// @param executionId the execution identifier, never null
/// @param workflowId the workflow definition identifier, never null
/// @param currentNodeId the node where execution is paused, may be null
/// @param createdAt when the execution was created, never null
public record ExecutionSummary(
        String executionId, String workflowId, String currentNodeId, Instant createdAt) {}
