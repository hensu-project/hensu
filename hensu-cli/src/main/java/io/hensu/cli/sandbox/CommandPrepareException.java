package io.hensu.cli.sandbox;

import java.io.Serial;

/// Signals that a command could not be prepared, and why.
///
/// Preparation fails for reasons a caller has to tell apart – arguments that
/// break their schema, a host with no working sandbox – so the exception carries
/// the {@link CommandResult} the failure corresponds to rather than only a
/// message. {@link CommandRunner#run} converts it straight back into that result,
/// and a caller with an approval gate can decide what the same outcome means
/// before anything runs.
///
/// @implNote **Immutable after construction.** Safe to share across Virtual Threads.
/// @see CommandRunner#prepare for the operation that raises this
public class CommandPrepareException extends RuntimeException {

    @Serial private static final long serialVersionUID = -8645660336590155816L;

    private final transient CommandResult result;

    /// Creates an exception carrying the outcome the failure corresponds to.
    ///
    /// @param result the outcome to report to the caller, not null
    public CommandPrepareException(CommandResult result) {
        super(result.message());
        this.result = result;
    }

    /// Creates an exception carrying an outcome and its underlying cause.
    ///
    /// @param result the outcome to report to the caller, not null
    /// @param cause the underlying failure, may be null
    public CommandPrepareException(CommandResult result, Throwable cause) {
        super(result.message(), cause);
        this.result = result;
    }

    /// Returns the outcome this failure corresponds to.
    ///
    /// @return the result, never null
    public CommandResult result() {
        return result;
    }
}
