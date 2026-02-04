package io.hensu.core.plan;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/// Result of executing an entire plan.
///
/// Captures the overall outcome, all step results, and aggregate statistics.
/// Used by {@link PlanExecutor} to report completion and by node executors
/// to determine transitions.
///
/// ### Contracts
/// - **Precondition**: `stepResults` must not be null
/// - **Postcondition**: All fields immutable after construction
///
/// @param status overall plan execution status, not null
/// @param stepResults results from each executed step, not null
/// @param failedAtStep index where failure occurred (-1 if no failure)
/// @param totalDuration aggregate execution time, not null
/// @param output final output from the last successful step, may be null
/// @param error error message if failed, may be null
/// @see StepResult for individual step outcomes
/// @see Plan for the executed plan
public record PlanResult(
        PlanStatus status,
        List<StepResult> stepResults,
        int failedAtStep,
        Duration totalDuration,
        Object output,
        String error) {

    /// Overall plan execution status.
    public enum PlanStatus {
        /// All steps completed successfully
        COMPLETED,
        /// A step failed without recovery
        FAILED,
        /// Plan was cancelled before completion
        CANCELLED,
        /// Plan exceeded time/resource limits
        TIMEOUT,
        /// Plan is still executing
        IN_PROGRESS
    }

    /// Compact constructor with validation.
    public PlanResult {
        Objects.requireNonNull(status, "status must not be null");
        stepResults = stepResults != null ? List.copyOf(stepResults) : List.of();
        totalDuration = totalDuration != null ? totalDuration : Duration.ZERO;
    }

    /// Creates a successful completion result.
    ///
    /// @param stepResults results from all steps, not null
    /// @return completed result, never null
    public static PlanResult completed(List<StepResult> stepResults) {
        Duration total =
                stepResults.stream()
                        .map(StepResult::duration)
                        .reduce(Duration.ZERO, Duration::plus);

        Object lastOutput = stepResults.isEmpty() ? null : stepResults.getLast().output();

        return new PlanResult(PlanStatus.COMPLETED, stepResults, -1, total, lastOutput, null);
    }

    /// Creates a failed result.
    ///
    /// @param failedAtStep index where failure occurred
    /// @param error error message, not null
    /// @return failed result, never null
    public static PlanResult failed(int failedAtStep, String error) {
        return new PlanResult(
                PlanStatus.FAILED, List.of(), failedAtStep, Duration.ZERO, null, error);
    }

    /// Creates a failed result with step results.
    ///
    /// @param stepResults results up to and including failure, not null
    /// @param failedAtStep index where failure occurred
    /// @param error error message, not null
    /// @return failed result, never null
    public static PlanResult failed(List<StepResult> stepResults, int failedAtStep, String error) {
        Duration total =
                stepResults.stream()
                        .map(StepResult::duration)
                        .reduce(Duration.ZERO, Duration::plus);

        return new PlanResult(PlanStatus.FAILED, stepResults, failedAtStep, total, null, error);
    }

    /// Creates a timeout result.
    ///
    /// @param stepResults results before timeout, not null
    /// @param maxDuration the exceeded duration limit
    /// @return timeout result, never null
    public static PlanResult timeout(List<StepResult> stepResults, Duration maxDuration) {
        return new PlanResult(
                PlanStatus.TIMEOUT,
                stepResults,
                stepResults.size(),
                maxDuration,
                null,
                "Plan exceeded maximum duration: " + maxDuration);
    }

    /// Creates a cancelled result.
    ///
    /// @param stepResults results before cancellation, not null
    /// @param reason cancellation reason
    /// @return cancelled result, never null
    public static PlanResult cancelled(List<StepResult> stepResults, String reason) {
        Duration total =
                stepResults.stream()
                        .map(StepResult::duration)
                        .reduce(Duration.ZERO, Duration::plus);

        return new PlanResult(
                PlanStatus.CANCELLED, stepResults, stepResults.size(), total, null, reason);
    }

    /// Returns whether the plan completed successfully.
    ///
    /// @return true if status is COMPLETED
    public boolean isSuccess() {
        return status == PlanStatus.COMPLETED;
    }

    /// Returns whether the plan failed.
    ///
    /// @return true if status is FAILED
    public boolean isFailure() {
        return status == PlanStatus.FAILED;
    }

    /// Returns the number of completed steps.
    ///
    /// @return count of steps that finished (success or failure)
    public int completedStepCount() {
        return (int) stepResults.stream().filter(StepResult::success).count();
    }

    /// Returns the number of failed steps.
    ///
    /// @return count of steps that failed
    public int failedStepCount() {
        return (int) stepResults.stream().filter(StepResult::isFailure).count();
    }
}
