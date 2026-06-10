package io.hensu.server.workflow;

import io.quarkus.runtime.annotations.RegisterForReflection;

/// Result of accepting a new workflow execution request.
///
/// @param executionId the assigned execution identifier, never null
/// @param workflowId the workflow that was started, never null
@RegisterForReflection
public record ExecutionStartResult(String executionId, String workflowId) {}
