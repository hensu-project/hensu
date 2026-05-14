package io.hensu.server.api;

import io.hensu.server.validation.ValidId;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/// Request body for starting an execution.
///
/// @param workflowId the workflow to execute, not null, not blank
/// @param context initial execution context variables, may be null
public record ExecutionStartRequest(
        @NotBlank(message = "workflowId is required") @ValidId String workflowId,
        Map<String, Object> context) {}
