package io.hensu.core.execution;

import java.io.Serial;

/// Stops a run in a way no layer of the engine may convert into an outcome.
///
/// The tool loop turns a provider's exception into a failed tool result, a fork turns
/// a sub-flow's exception into a failed branch, and the router turns a provider's
/// exception into a failure the model reads. Each is right for an ordinary failure and
/// wrong for these: a run whose claim is gone must stop rather than tell its model
/// that a tool failed, and a run whose replay diverged must not continue at all. Every
/// such catch rethrows this type.
///
/// ### Permitted Subtypes
/// - {@link RunPreemptedException} – stop without recording anything; another run
///   continues the execution
/// - {@link ReplayDivergenceException} – the execution fails
///
/// @see ExecutionJournal for the records whose writes can preempt a run
public abstract sealed class RunAbortedException extends RuntimeException
        permits RunPreemptedException, ReplayDivergenceException {

    @Serial private static final long serialVersionUID = -8912032713689343320L;

    /// @param message what stopped the run, not null
    /// @param cause the underlying failure, may be null
    protected RunAbortedException(String message, Throwable cause) {
        super(message, cause);
    }
}
