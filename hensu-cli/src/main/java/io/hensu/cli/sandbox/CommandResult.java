package io.hensu.cli.sandbox;

import io.hensu.core.tool.ToolCallStatus;

/// The outcome of one command execution.
///
/// The status reuses {@link ToolCallStatus} rather than introducing a second
/// vocabulary: its constants were defined for exactly these outcomes, and sharing
/// them means a tool provider can copy a result into a
/// {@link io.hensu.core.tool.ToolCallResult} field by field with no translation
/// table that could fall out of step.
///
/// @param status what happened, not null
/// @param exitCode the process exit code, or -1 when no process ran
/// @param output the merged stdout and stderr, not null (may be empty)
/// @param message the reason for a non-success status, not null (may be empty)
/// @implNote **Immutable after construction.** Safe to share across Virtual Threads.
/// @see CommandRunner for the producer
public record CommandResult(ToolCallStatus status, int exitCode, String output, String message) {

    /// Exit code recorded when the command never became a process.
    public static final int NO_PROCESS = -1;

    /// Compact constructor normalising the nullable text fields.
    public CommandResult {
        output = output != null ? output : "";
        message = message != null ? message : "";
    }

    /// Creates the result of a command that ran and exited zero.
    ///
    /// @param output the merged output, not null
    /// @return the result, never null
    public static CommandResult success(String output) {
        return new CommandResult(ToolCallStatus.SUCCESS, 0, output, "");
    }

    /// Creates the result of a command that ran and exited non-zero.
    ///
    /// @param exitCode the process exit code
    /// @param output the merged output, not null
    /// @return the result, never null
    public static CommandResult failure(int exitCode, String output) {
        return new CommandResult(
                ToolCallStatus.FAILURE, exitCode, output, "exited with code " + exitCode);
    }

    /// Creates the result of a command rejected before any process was spawned.
    ///
    /// @param reason why the arguments were rejected, not null
    /// @return the result, never null
    public static CommandResult validationFailure(String reason) {
        return new CommandResult(ToolCallStatus.VALIDATION_FAILED, NO_PROCESS, "", reason);
    }

    /// Creates the result of a command refused because nothing could contain it.
    ///
    /// @param reason the backend's own diagnostics, not null
    /// @return the result, never null
    public static CommandResult sandboxUnavailable(String reason) {
        return new CommandResult(ToolCallStatus.SANDBOX_UNAVAILABLE, NO_PROCESS, "", reason);
    }

    /// Creates the result of a command whose containment failed at launch.
    ///
    /// @param reason what the supervisor reported, not null
    /// @return the result, never null
    public static CommandResult sandboxRefused(String reason) {
        return new CommandResult(ToolCallStatus.SANDBOX_REFUSED, NO_PROCESS, "", reason);
    }

    /// Creates the result of a command that outlived its wall clock.
    ///
    /// @param timeoutMs the deadline that was exceeded
    /// @param output whatever the command had produced, not null
    /// @return the result, never null
    public static CommandResult timeout(long timeoutMs, String output) {
        return new CommandResult(
                ToolCallStatus.TIMEOUT, NO_PROCESS, output, "timed out after " + timeoutMs + "ms");
    }

    /// Returns whether the command ran and reported success.
    ///
    /// @return true if the status is {@link ToolCallStatus#SUCCESS}
    public boolean success() {
        return status == ToolCallStatus.SUCCESS;
    }
}
