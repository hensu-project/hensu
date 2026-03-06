package io.hensu.cli.execution;

import java.io.PrintStream;

/// Output destination for workflow execution output.
///
/// Abstracts the print destination so execution output can be directed to the
/// local terminal (inline mode) or to a daemon socket (daemon mode). Both
/// {@link #out()} and {@link #err()} return {@link PrintStream} instances so all
/// existing {@code System.out.printf/println} call-sites only need a one-line
/// change.
///
/// ### Implementations
/// - {@link LocalExecutionSink} — writes to {@code System.out}/{@code System.err} (inline)
/// - {@link DaemonExecutionSink} — encodes bytes as NDJSON frames and broadcasts to
/// daemon subscribers
///
/// See `io.hensu.cli.commands.WorkflowRunCommand`
public interface ExecutionSink {

    /// Returns the standard output stream for execution output.
    ///
    /// @return non-null print stream
    PrintStream out();

    /// Returns the error output stream for error messages.
    ///
    /// @return non-null print stream
    PrintStream err();
}
