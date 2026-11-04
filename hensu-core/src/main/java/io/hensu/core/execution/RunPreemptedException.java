package io.hensu.core.execution;

import java.io.Serial;

/// The run must stop without recording anything further.
///
/// Thrown when a write the run depends on is refused because the run's claim on the
/// execution has been superseded, or cannot be made because the store that records
/// the run's progress is unreachable. Neither is a failure of the execution: it stays
/// claimable, and whichever run holds it next continues from its last durable step.
/// Recording a failure here would be refused, and would be false if it were not.
public final class RunPreemptedException extends RunAbortedException {

    @Serial private static final long serialVersionUID = -2620634921306113338L;

    /// @param message why the run stopped, not null
    public RunPreemptedException(String message) {
        super(message, null);
    }

    /// @param message why the run stopped, not null
    /// @param cause the write failure, not null
    public RunPreemptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
