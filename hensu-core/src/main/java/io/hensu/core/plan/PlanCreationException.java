package io.hensu.core.plan;

import java.io.Serial;

/// Thrown when a plan cannot be created.
///
/// Indicates that the planner could not generate a valid plan for the
/// given goal and constraints. Common causes:
/// - No tools available to achieve the goal
/// - Goal cannot be decomposed into available tools
/// - Constraints are too restrictive (e.g., maxSteps too low)
///
/// @see Planner#createPlan for plan creation
public class PlanCreationException extends Exception {

    @Serial private static final long serialVersionUID = -6935499511500220978L;

    /// Creates exception with message.
    ///
    /// @param message description of why plan creation failed
    public PlanCreationException(String message) {
        super(message);
    }

    /// Creates exception with message and cause.
    ///
    /// @param message description of why plan creation failed
    /// @param cause the underlying exception
    public PlanCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}
