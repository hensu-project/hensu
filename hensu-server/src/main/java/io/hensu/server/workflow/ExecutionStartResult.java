package io.hensu.server.workflow;

/// Result of accepting a new workflow execution request.
///
/// @param executionId the assigned execution identifier, never null
/// @param workflowId the workflow that was started, never null
public record ExecutionStartResult(String executionId, String workflowId) {}
