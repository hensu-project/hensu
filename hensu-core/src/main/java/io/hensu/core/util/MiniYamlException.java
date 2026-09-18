package io.hensu.core.util;

import java.io.Serial;

/// Signals that a document rejected by {@link MiniYaml} could not be parsed or
/// could not be read as the shape the caller asked for.
///
/// The exception always carries the source line that caused the failure so a
/// configuration error points at the text the operator has to fix rather than
/// at the loader that discovered it.
///
/// @implNote **Immutable after construction.** Safe to share across Virtual Threads.
/// @see MiniYaml for the supported grammar
public class MiniYamlException extends RuntimeException {

    @Serial private static final long serialVersionUID = -5730124588389362414L;

    private final int line;

    /// Creates an exception describing a failure at a known source line.
    ///
    /// @param message what is wrong with the document, not null
    /// @param line one-based source line the failure refers to
    public MiniYamlException(String message, int line) {
        super("line " + line + ": " + message);
        this.line = line;
    }

    /// Returns the one-based source line this failure refers to.
    ///
    /// @return the source line number
    public int line() {
        return line;
    }
}
