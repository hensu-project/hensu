package io.hensu.core.tool.file;

import io.hensu.core.tool.ToolCallStatus;
import java.io.Serial;
import java.util.Objects;

/// Signals that a file tool refused an argument or could not complete a call.
///
/// The exception carries the {@link ToolCallStatus} the refusal maps onto, so
/// {@link FileToolProvider} turns it into a result without a translation table
/// and the agent reads one vocabulary across every tool source.
///
/// A path that escapes the confinement root, and a write aimed at a protected
/// configuration file, are both {@link ToolCallStatus#VALIDATION_FAILED} rather
/// than {@link ToolCallStatus#DENIED}: the capability is present and the
/// argument is wrong. Reporting them as denials would list them as capability
/// gaps, inviting an operator to grant what is a fixed boundary.
///
/// @implNote **Immutable after construction.** Safe to share across Virtual Threads.
/// @see FileToolProvider for the tools that raise this
public class FileToolException extends RuntimeException {

    @Serial private static final long serialVersionUID = 4602214548719927737L;

    private final transient ToolCallStatus status;

    /// Creates a refusal carrying the status it maps onto.
    ///
    /// @param status the outcome this refusal reports, not null
    /// @param message what the caller has to change, not null
    public FileToolException(ToolCallStatus status, String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    /// Creates a refusal carrying the status it maps onto and its cause.
    ///
    /// @param status the outcome this refusal reports, not null
    /// @param message what the caller has to change, not null
    /// @param cause the underlying failure, may be null
    public FileToolException(ToolCallStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    /// Creates a refusal of an argument the tool cannot accept.
    ///
    /// @param message what the caller has to change, not null
    /// @return refusal reporting {@link ToolCallStatus#VALIDATION_FAILED}, never null
    public static FileToolException invalid(String message) {
        return new FileToolException(ToolCallStatus.VALIDATION_FAILED, message);
    }

    /// Returns the outcome this refusal reports.
    ///
    /// @return the status, never null
    public ToolCallStatus status() {
        return status;
    }
}
