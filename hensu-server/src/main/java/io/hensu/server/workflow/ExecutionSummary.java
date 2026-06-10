package io.hensu.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;

/// Summary of an execution.
///
/// Nullable fields are omitted from JSON when {@code null}.
///
/// @param executionId the execution identifier, never null
/// @param workflowId the workflow definition identifier, never null
/// @param currentNodeId the node where execution is paused, null if not applicable
/// @param createdAt when the execution was created, never null
/// @param correlationId the correlation ID required to resume, null unless paused for review
@RegisterForReflection
public record ExecutionSummary(
        String executionId,
        String workflowId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String currentNodeId,
        Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String correlationId) {}
