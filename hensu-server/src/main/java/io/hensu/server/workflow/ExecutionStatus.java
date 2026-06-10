package io.hensu.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/// Status of an execution.
///
/// Nullable fields are omitted from JSON when {@code null}.
///
/// @param executionId the execution identifier, never null
/// @param workflowId the workflow definition identifier, never null
/// @param status `COMPLETED` or `PAUSED`, never null
/// @param currentNodeId the node where execution is positioned, null if completed
/// @param hasPendingPlan true if a plan is awaiting review
/// @param correlationId the correlation ID required to resume, null unless paused for review
@RegisterForReflection
public record ExecutionStatus(
        String executionId,
        String workflowId,
        String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String currentNodeId,
        boolean hasPendingPlan,
        @JsonInclude(JsonInclude.Include.NON_NULL) String correlationId) {}
