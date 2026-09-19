package io.hensu.core.tool;

import java.util.List;
import java.util.Objects;

/// Result of executing a tool call, fed back to the agent for the next round.
///
/// The outcome is typed rather than boolean: an unattended run has to tell a
/// refusal apart from a broken tool, and the audit trail records the decision
/// alongside the exit status. {@link #success()} is derived from the status, so
/// call sites that only care whether the tool worked read unchanged.
///
/// @param toolName name of the tool that was invoked, not null
/// @param status the invocation outcome, not null
/// @param output tool output on success, may be null
/// @param error error message when the outcome is not success, may be null
/// @param exitCode process exit code for tools that run one, may be null
/// @param argv the resolved invocation, not null (empty when the call runs no process)
/// @see ToolCallStatus for the outcome vocabulary
public record ToolCallResult(
        String toolName,
        ToolCallStatus status,
        String output,
        String error,
        Integer exitCode,
        List<String> argv) {

    /// Compact constructor with validation and a defensive copy of the argv.
    public ToolCallResult {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        argv = argv != null ? List.copyOf(argv) : List.of();
    }

    /// Creates a result for a call that resolved no argv.
    ///
    /// @param toolName name of the tool that was invoked, not null
    /// @param status the invocation outcome, not null
    /// @param output tool output on success, may be null
    /// @param error error message when the outcome is not success, may be null
    /// @param exitCode process exit code for tools that run one, may be null
    public ToolCallResult(
            String toolName, ToolCallStatus status, String output, String error, Integer exitCode) {
        this(toolName, status, output, error, exitCode, List.of());
    }

    /// Returns a copy carrying the invocation that produced it.
    ///
    /// The argv is attached by whoever resolved it, which is not the provider that ran
    /// the call but the layer that described it first: the approval decorator holds the
    /// only resolved argv a human ever saw, and the audit trail has to record that same
    /// one rather than a second resolution of the same template.
    ///
    /// @param argv the resolved argv, may be null or empty
    /// @return a copy with the argv attached, never null
    public ToolCallResult withArgv(List<String> argv) {
        return new ToolCallResult(toolName, status, output, error, exitCode, argv);
    }

    /// Creates a successful tool call result.
    ///
    /// @param toolName tool that was invoked, not null
    /// @param output tool output text, not null
    /// @return successful result, never null
    public static ToolCallResult success(String toolName, String output) {
        return new ToolCallResult(toolName, ToolCallStatus.SUCCESS, output, null, null);
    }

    /// Creates a successful tool call result carrying a process exit code.
    ///
    /// @param toolName tool that was invoked, not null
    /// @param output tool output text, not null
    /// @param exitCode exit code reported by the underlying process, may be null
    /// @return successful result, never null
    public static ToolCallResult success(String toolName, String output, Integer exitCode) {
        return new ToolCallResult(toolName, ToolCallStatus.SUCCESS, output, null, exitCode);
    }

    /// Creates a failed tool call result, meaning the tool ran and reported failure.
    ///
    /// Outcomes that are not the tool's own failure – a refusal, a timeout, an
    /// unroutable name – use {@link #of} with the matching status instead.
    ///
    /// @param toolName tool that was invoked, not null
    /// @param error error description, not null
    /// @return failure result, never null
    public static ToolCallResult failure(String toolName, String error) {
        return new ToolCallResult(toolName, ToolCallStatus.FAILURE, null, error, null);
    }

    /// Creates a result with an explicit status.
    ///
    /// @param toolName tool that was invoked, not null
    /// @param status the outcome, not null
    /// @param output tool output, may be null
    /// @param error error description, may be null
    /// @param exitCode process exit code, may be null
    /// @return result, never null
    public static ToolCallResult of(
            String toolName, ToolCallStatus status, String output, String error, Integer exitCode) {
        return new ToolCallResult(toolName, status, output, error, exitCode);
    }

    /// Returns whether the tool ran and reported success.
    ///
    /// @return true if the status is {@link ToolCallStatus#SUCCESS}
    public boolean success() {
        return status == ToolCallStatus.SUCCESS;
    }

    /// Returns the text fed back to the agent for this outcome.
    ///
    /// Statuses other than success and failure are prefixed with the status
    /// name, so a model can tell a refusal from a broken tool and stop
    /// retrying a call that will never be permitted.
    ///
    /// @return text representation for feeding back to the agent, never null
    public String asText() {
        return switch (status) {
            case SUCCESS -> output != null ? output : "";
            case FAILURE -> "ERROR: " + (error != null ? error : "");
            default -> status.name() + ": " + (error != null ? error : "");
        };
    }
}
