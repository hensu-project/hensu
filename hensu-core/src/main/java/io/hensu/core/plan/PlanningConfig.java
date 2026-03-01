package io.hensu.core.plan;

import java.time.Duration;

/// Configuration for node-level planning behavior.
///
/// Controls how a node generates and executes plans. Used by
/// `StandardNode` to determine execution strategy.
///
/// ### Modes
/// - **DISABLED**: Traditional direct execution (no planning)
/// - **STATIC**: Use predefined plan from DSL
/// - **DYNAMIC**: LLM generates plan at runtime
///
/// ### Contracts
/// - **Precondition**: `mode` and `constraints` must not be null
/// - **Postcondition**: All fields immutable after construction
///
/// ### Usage
/// {@snippet :
/// // Static plan configuration
/// PlanningConfig staticConfig = PlanningConfig.forStatic();
///
/// // Dynamic planning with review
/// PlanningConfig dynamicConfig = new PlanningConfig(
///     PlanningMode.DYNAMIC,
///     PlanConstraints.defaults(),
///     true  // review before execute
/// );
/// }
///
/// @param mode how planning is handled, not null
/// @param constraints limits on plan generation and execution, not null
/// @param review whether to enable review gates before and after plan execution;
///              when {@code true} both the pre-execution gate (review the plan structure)
///              and the post-execution gate (review the plan results) are activated
/// @see PlanningMode for mode options
/// @see PlanConstraints for constraint details
public record PlanningConfig(PlanningMode mode, PlanConstraints constraints, boolean review) {

    /// Compact constructor with validation.
    public PlanningConfig {
        mode = mode != null ? mode : PlanningMode.DISABLED;
        constraints = constraints != null ? constraints : PlanConstraints.defaults();
    }

    /// Returns configuration for disabled planning (direct execution).
    ///
    /// @return disabled planning config, never null
    public static PlanningConfig disabled() {
        return new PlanningConfig(PlanningMode.DISABLED, PlanConstraints.defaults(), false);
    }

    /// Returns configuration for static (DSL-defined) plans.
    ///
    /// @return static planning config, never null
    public static PlanningConfig forStatic() {
        return new PlanningConfig(PlanningMode.STATIC, PlanConstraints.forStaticPlan(), false);
    }

    /// Returns configuration for static plans with review.
    ///
    /// @return static planning config with review, never null
    public static PlanningConfig forStaticWithReview() {
        return new PlanningConfig(PlanningMode.STATIC, PlanConstraints.forStaticPlan(), true);
    }

    /// Returns configuration for dynamic (LLM-generated) plans.
    ///
    /// @return dynamic planning config, never null
    public static PlanningConfig forDynamic() {
        return new PlanningConfig(PlanningMode.DYNAMIC, PlanConstraints.defaults(), false);
    }

    /// Returns configuration for dynamic plans with review gates enabled.
    ///
    /// @return dynamic planning config with review, never null
    public static PlanningConfig forDynamicWithReview() {
        return new PlanningConfig(PlanningMode.DYNAMIC, PlanConstraints.defaults(), true);
    }

    /// Returns whether planning is enabled.
    ///
    /// @return true if mode is not DISABLED
    public boolean isEnabled() {
        return mode != PlanningMode.DISABLED;
    }

    /// Returns whether this uses a static (DSL-defined) plan.
    ///
    /// @return true if mode is STATIC
    public boolean isStatic() {
        return mode == PlanningMode.STATIC;
    }

    /// Returns whether this uses dynamic (LLM-generated) plans.
    ///
    /// @return true if mode is DYNAMIC
    public boolean isDynamic() {
        return mode == PlanningMode.DYNAMIC;
    }

    /// Returns a copy with updated constraints.
    ///
    /// @param newConstraints the new constraints, not null
    /// @return new config with updated constraints, never null
    public PlanningConfig withConstraints(PlanConstraints newConstraints) {
        return new PlanningConfig(mode, newConstraints, review);
    }

    /// Returns a copy with review gates enabled (both pre- and post-execution).
    ///
    /// @return new config with review enabled, never null
    public PlanningConfig withReview() {
        return new PlanningConfig(mode, constraints, true);
    }

    /// Returns a copy with review gates disabled.
    ///
    /// @return new config with review disabled, never null
    public PlanningConfig withoutReview() {
        return new PlanningConfig(mode, constraints, false);
    }

    /// Returns a copy with updated max duration.
    ///
    /// @param duration the new max duration, not null
    /// @return new config with updated duration, never null
    public PlanningConfig withMaxDuration(Duration duration) {
        return new PlanningConfig(mode, constraints.withMaxDuration(duration), review);
    }

    /// Returns a copy with updated max steps.
    ///
    /// @param maxSteps the new max steps
    /// @return new config with updated steps, never null
    public PlanningConfig withMaxSteps(int maxSteps) {
        return new PlanningConfig(mode, constraints.withMaxSteps(maxSteps), review);
    }
}
