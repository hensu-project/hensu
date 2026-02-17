package io.hensu.core.workflow;

import io.hensu.core.observability.ObservabilityConfig;

public record WorkflowConfig(
        Long maxExecutionTime,
        Boolean checkpoints,
        Long checkpointInterval,
        ObservabilityConfig observability) {}
