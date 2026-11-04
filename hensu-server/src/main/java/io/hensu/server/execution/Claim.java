package io.hensu.server.execution;

import java.util.Objects;

/// One run's hold on an execution.
///
/// Every write the run makes – checkpoint, journal record, tool intent, terminal state –
/// carries the epoch and commits only while it is still the execution's current one. A
/// claim is taken by starting, resuming or picking up an execution, and each of those
/// increments the epoch, so a run that lost its claim cannot write over its successor no
/// matter how long it was paused or which node it runs on.
///
/// @param tenantId the tenant owning the execution, not null
/// @param executionId the execution, not null
/// @param epoch the claim's epoch, positive
public record Claim(String tenantId, String executionId, long epoch) {

    public Claim {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        if (epoch < 1) {
            throw new IllegalArgumentException("epoch must be positive");
        }
    }

    @Override
    public String toString() {
        return tenantId + "/" + executionId + "@" + epoch;
    }
}
