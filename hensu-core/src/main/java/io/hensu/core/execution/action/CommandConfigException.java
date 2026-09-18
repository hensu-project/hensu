package io.hensu.core.execution.action;

import java.io.Serial;

/// Signals that a command catalog is unusable as written.
///
/// Every rule the catalog enforces – argv templates, executable resolution,
/// shell-mode restrictions, sandbox scopes – is checked when the file is loaded
/// rather than when a command runs, so a misconfigured catalog fails in front of
/// the operator instead of in front of an agent. The failure carries the source
/// line so the message names the text to fix.
///
/// @implNote **Immutable after construction.** Safe to share across Virtual Threads.
/// @see CommandRegistry#loadFromFile for the load path that raises this
public class CommandConfigException extends RuntimeException {

    @Serial private static final long serialVersionUID = -1168438973790064831L;

    private final int line;

    /// Creates an exception describing a rejected catalog entry.
    ///
    /// @param message what is wrong with the catalog, not null
    /// @param line one-based source line the failure refers to
    public CommandConfigException(String message, int line) {
        super("line " + line + ": " + message);
        this.line = line;
    }

    /// Creates an exception describing a rejected catalog entry with a cause.
    ///
    /// @param message what is wrong with the catalog, not null
    /// @param line one-based source line the failure refers to
    /// @param cause the underlying failure, may be null
    public CommandConfigException(String message, int line, Throwable cause) {
        super("line " + line + ": " + message, cause);
        this.line = line;
    }

    /// Returns the one-based source line this failure refers to.
    ///
    /// @return the source line number
    public int line() {
        return line;
    }
}
