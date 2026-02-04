package io.hensu.core.plan;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/// Result of executing a single plan step.
///
/// Captures the outcome, timing, and any output or error from tool invocation.
/// Used by {@link PlanExecutor} to track progress and by observers to monitor
/// execution.
///
/// ### Contracts
/// - **Precondition**: `toolName` must not be null
/// - **Postcondition**: All fields immutable after construction
///
/// @param stepIndex which step produced this result (zero-based)
/// @param toolName the tool that was invoked, not null
/// @param success whether execution succeeded
/// @param output result value if successful, may be null
/// @param error error message if failed, may be null
/// @param duration execution time, not null
/// @param metadata additional execution metadata, not null
/// @see PlannedStep for the step definition
/// @see PlanResult for aggregate plan outcome
public record StepResult(
        int stepIndex,
        String toolName,
        boolean success,
        Object output,
        String error,
        Duration duration,
        Map<String, Object> metadata) {

    /// Compact constructor with validation.
    public StepResult {
        Objects.requireNonNull(toolName, "toolName must not be null");
        duration = duration != null ? duration : Duration.ZERO;
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /// Creates a successful result.
    ///
    /// @param stepIndex step position in plan
    /// @param toolName tool that was invoked, not null
    /// @param output execution output, may be null
    /// @param duration execution time, not null
    /// @return successful result, never null
    public static StepResult success(
            int stepIndex, String toolName, Object output, Duration duration) {
        return new StepResult(stepIndex, toolName, true, output, null, duration, Map.of());
    }

    /// Creates a failed result.
    ///
    /// @param stepIndex step position in plan
    /// @param toolName tool that was invoked, not null
    /// @param error error message, not null
    /// @param duration execution time, not null
    /// @return failed result, never null
    public static StepResult failure(
            int stepIndex, String toolName, String error, Duration duration) {
        return new StepResult(stepIndex, toolName, false, null, error, duration, Map.of());
    }

    /// Returns a copy with additional metadata.
    ///
    /// @param key metadata key, not null
    /// @param value metadata value
    /// @return new result with updated metadata, never null
    public StepResult withMetadata(String key, Object value) {
        Map<String, Object> newMeta = new java.util.HashMap<>(metadata);
        newMeta.put(key, value);
        return new StepResult(stepIndex, toolName, success, output, error, duration, newMeta);
    }

    /// Returns whether execution failed.
    ///
    /// @return true if not successful
    public boolean isFailure() {
        return !success;
    }
}
