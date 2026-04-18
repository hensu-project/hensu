package io.hensu.server.workflow;

import java.util.Map;

/// The public output of a completed or paused execution.
///
/// Contains the workflow context at termination with internal system keys
/// (prefixed with `_`) excluded.
///
/// @param executionId the execution identifier, never null
/// @param workflowId the workflow definition identifier, never null
/// @param status `COMPLETED` or `PAUSED`, never null
/// @param output public context variables produced by the workflow, never null, may be empty
public record ExecutionOutput(
        String executionId, String workflowId, String status, Map<String, Object> output) {}
