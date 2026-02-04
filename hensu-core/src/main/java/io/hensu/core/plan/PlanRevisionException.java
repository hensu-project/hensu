package io.hensu.core.plan;

import java.io.Serial;

/// Thrown when a plan cannot be revised.
///
/// Indicates that the planner does not support revision or the revision
/// request is invalid. Common causes:
/// - Static plans do not support revision
/// - Maximum replans exceeded
/// - Revision context is invalid
///
/// @see Planner#revisePlan for plan revision
public class PlanRevisionException extends Exception {

    @Serial private static final long serialVersionUID = -744290150336834061L;

    /// Creates exception with message.
    ///
    /// @param message description of why revision failed
    public PlanRevisionException(String message) {
        super(message);
    }

    /// Creates exception with message and cause.
    ///
    /// @param message description of why revision failed
    /// @param cause the underlying exception
    public PlanRevisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
