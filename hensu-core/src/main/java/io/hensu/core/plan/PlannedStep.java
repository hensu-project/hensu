package io.hensu.core.plan;

import java.util.Map;
import java.util.Objects;

/// Represents a single step in an execution plan.
///
/// Each step specifies a tool to invoke with arguments. Steps are executed
/// sequentially by the {@link PlanExecutor} unless the plan is revised.
///
/// ### Contracts
/// - **Precondition**: `toolName` must not be null or blank
/// - **Postcondition**: All fields immutable after construction
/// - **Invariant**: `index` >= 0
///
/// @param index position in the plan (zero-based)
/// @param toolName the tool to invoke, not null
/// @param arguments tool parameters with `{variable}` placeholders, not null
/// @param description human-readable step description for logging, not null
/// @param status current execution status, not null
/// @see Plan for the parent container
/// @see StepResult for execution outcome
public record PlannedStep(
        int index,
        String toolName,
        Map<String, Object> arguments,
        String description,
        StepStatus status) {

    /// Step execution status.
    public enum StepStatus {
        /// Step not yet started
        PENDING,
        /// Step currently executing
        EXECUTING,
        /// Step completed successfully
        COMPLETED,
        /// Step failed with error
        FAILED,
        /// Step skipped (plan revised)
        SKIPPED
    }

    /// Compact constructor with validation.
    public PlannedStep {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
        description = description != null ? description : "";
        status = status != null ? status : StepStatus.PENDING;
    }

    /// Creates a pending step.
    ///
    /// @param index step position in plan
    /// @param toolName tool to invoke, not null
    /// @param arguments tool parameters, not null
    /// @param description human-readable description
    /// @return new pending step, never null
    public static PlannedStep pending(
            int index, String toolName, Map<String, Object> arguments, String description) {
        return new PlannedStep(index, toolName, arguments, description, StepStatus.PENDING);
    }

    /// Creates a step with no arguments.
    ///
    /// @param index step position in plan
    /// @param toolName tool to invoke, not null
    /// @param description human-readable description
    /// @return new pending step, never null
    public static PlannedStep simple(int index, String toolName, String description) {
        return new PlannedStep(index, toolName, Map.of(), description, StepStatus.PENDING);
    }

    /// Returns a copy with updated status.
    ///
    /// @param newStatus the new status, not null
    /// @return new step with updated status, never null
    public PlannedStep withStatus(StepStatus newStatus) {
        return new PlannedStep(index, toolName, arguments, description, newStatus);
    }

    /// Returns whether this step has completed (success or failure).
    ///
    /// @return true if status is COMPLETED or FAILED
    public boolean isFinished() {
        return status == StepStatus.COMPLETED
                || status == StepStatus.FAILED
                || status == StepStatus.SKIPPED;
    }

    /// Returns whether this step succeeded.
    ///
    /// @return true if status is COMPLETED
    public boolean isSuccess() {
        return status == StepStatus.COMPLETED;
    }
}
