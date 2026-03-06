package io.hensu.cli.execution;

import java.io.PrintStream;

/// {@link ExecutionSink} that writes directly to the local terminal.
///
/// Used in inline mode (no daemon running or explicitly bypassed). Zero-cost
/// wrapper over {@code System.out} and {@code System.err} — all existing
/// behavior is preserved exactly.
///
/// @see DaemonExecutionSink
public final class LocalExecutionSink implements ExecutionSink {

    /// Shared singleton — stateless and thread-safe.
    public static final LocalExecutionSink INSTANCE = new LocalExecutionSink();

    private LocalExecutionSink() {}

    @Override
    public PrintStream out() {
        return System.out;
    }

    @Override
    public PrintStream err() {
        return System.err;
    }
}
