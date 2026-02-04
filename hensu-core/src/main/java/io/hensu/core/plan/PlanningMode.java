package io.hensu.core.plan;

/// How planning is handled for a node.
///
/// Determines whether a node executes directly, uses a predefined plan,
/// or generates a plan dynamically at runtime.
///
/// @see PlanningConfig for configuration
public enum PlanningMode {
    /// No planning, direct agent execution (default behavior)
    DISABLED,

    /// Use predefined plan from DSL `plan { }` block
    STATIC,

    /// LLM generates plan at runtime based on goal
    DYNAMIC
}
