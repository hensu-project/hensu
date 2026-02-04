package io.hensu.core.plan;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/// Immutable snapshot of plan execution state for persistence and resume.
///
/// Captures the current position within a plan's step sequence, enabling:
/// - Checkpoint-based state persistence
/// - Resume from interrupted plan execution
/// - State transfer between server instances
///
/// ### Contracts
/// - **Precondition**: `nodeId` must not be null
/// - **Postcondition**: All fields immutable after construction
/// - **Invariant**: `currentStepIndex` >= 0
///
/// @param nodeId the workflow node this plan belongs to, not null
/// @param steps the planned steps to execute, not null (may be empty)
/// @param currentStepIndex zero-based index of next step to execute
/// @param completedResults results from previously completed steps, not null
/// @see io.hensu.core.state.HensuSnapshot for parent workflow state
/// @see PlannedStep for individual step representation
public record PlanSnapshot(
        String nodeId,
        List<PlannedStepSnapshot> steps,
        int currentStepIndex,
        List<StepResultSnapshot> completedResults)
        implements Serializable {

    /// Serializable snapshot of a planned step.
    ///
    /// @param index step position in plan (zero-based)
    /// @param toolName the tool to invoke
    /// @param arguments tool parameters, not null
    /// @param description human-readable step description
    /// @param status current execution status
    public record PlannedStepSnapshot(
            int index,
            String toolName,
            Map<String, Object> arguments,
            String description,
            String status)
            implements Serializable {}

    /// Serializable snapshot of a step result.
    ///
    /// @param stepIndex which step produced this result
    /// @param toolName the tool that was invoked
    /// @param success whether execution succeeded
    /// @param output result value if successful
    /// @param error error message if failed
    /// @param durationMillis execution time in milliseconds
    public record StepResultSnapshot(
            int stepIndex,
            String toolName,
            boolean success,
            Object output,
            String error,
            long durationMillis)
            implements Serializable {}

    /// Compact constructor with validation.
    public PlanSnapshot {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        if (currentStepIndex < 0) {
            throw new IllegalArgumentException("currentStepIndex must be >= 0");
        }
        steps = steps != null ? List.copyOf(steps) : List.of();
        completedResults = completedResults != null ? List.copyOf(completedResults) : List.of();
    }

    /// Creates an empty snapshot for a node with no active plan.
    ///
    /// @param nodeId the node identifier, not null
    /// @return empty plan snapshot, never null
    public static PlanSnapshot empty(String nodeId) {
        return new PlanSnapshot(nodeId, List.of(), 0, List.of());
    }

    /// Returns whether this plan has completed all steps.
    ///
    /// @return true if all steps have been executed
    public boolean isComplete() {
        return currentStepIndex >= steps.size();
    }

    /// Returns the number of remaining steps.
    ///
    /// @return count of steps not yet executed
    public int remainingSteps() {
        return Math.max(0, steps.size() - currentStepIndex);
    }
}
