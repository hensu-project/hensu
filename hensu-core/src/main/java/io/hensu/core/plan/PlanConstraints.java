package io.hensu.core.plan;

import java.time.Duration;

/// Constraints for plan generation and execution.
///
/// Limits resource usage during plan execution to prevent runaway
/// processes and ensure predictable behavior.
///
/// ### Contracts
/// - **Precondition**: All numeric values must be non-negative
/// - **Postcondition**: All fields immutable after construction
///
/// @param maxSteps maximum number of steps in a plan
/// @param maxReplans maximum number of times a plan can be revised
/// @param maxDuration maximum total execution time, not null
/// @param maxTokenBudget maximum LLM tokens for planning (0 = unlimited)
/// @param allowReplan whether plan revision is allowed on failure
/// @see Plan for the constrained entity
/// @see PlanExecutor for constraint enforcement
public record PlanConstraints(
        int maxSteps,
        int maxReplans,
        Duration maxDuration,
        int maxTokenBudget,
        boolean allowReplan) {

    /// Compact constructor with validation.
    public PlanConstraints {
        if (maxSteps < 0) {
            throw new IllegalArgumentException("maxSteps must be >= 0");
        }
        if (maxReplans < 0) {
            throw new IllegalArgumentException("maxReplans must be >= 0");
        }
        if (maxDuration == null) {
            maxDuration = Duration.ofMinutes(5);
        }
        if (maxTokenBudget < 0) {
            throw new IllegalArgumentException("maxTokenBudget must be >= 0");
        }
    }

    /// Returns default constraints suitable for most workflows.
    ///
    /// Defaults:
    /// - maxSteps: 10
    /// - maxReplans: 3
    /// - maxDuration: 5 minutes
    /// - maxTokenBudget: 10000
    /// - allowReplan: true
    ///
    /// @return default constraints, never null
    public static PlanConstraints defaults() {
        return new PlanConstraints(10, 3, Duration.ofMinutes(5), 10000, true);
    }

    /// Returns constraints that disallow replanning.
    ///
    /// @return constraints with allowReplan=false, never null
    public static PlanConstraints noReplan() {
        return new PlanConstraints(10, 0, Duration.ofMinutes(5), 10000, false);
    }

    /// Returns constraints for static plans (no replanning, unlimited tokens).
    ///
    /// @return static plan constraints, never null
    public static PlanConstraints forStaticPlan() {
        return new PlanConstraints(100, 0, Duration.ofMinutes(30), 0, false);
    }

    /// Returns a copy with updated maxSteps.
    ///
    /// @param steps new max steps value
    /// @return new constraints, never null
    public PlanConstraints withMaxSteps(int steps) {
        return new PlanConstraints(steps, maxReplans, maxDuration, maxTokenBudget, allowReplan);
    }

    /// Returns a copy with updated maxDuration.
    ///
    /// @param duration new max duration, not null
    /// @return new constraints, never null
    public PlanConstraints withMaxDuration(Duration duration) {
        return new PlanConstraints(maxSteps, maxReplans, duration, maxTokenBudget, allowReplan);
    }

    /// Returns a copy with replanning disabled.
    ///
    /// @return new constraints with allowReplan=false, never null
    public PlanConstraints withoutReplan() {
        return new PlanConstraints(maxSteps, 0, maxDuration, maxTokenBudget, false);
    }
}
