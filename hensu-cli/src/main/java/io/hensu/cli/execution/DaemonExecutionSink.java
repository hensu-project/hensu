package io.hensu.cli.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.cli.daemon.BroadcastOutputStream;
import io.hensu.cli.daemon.StoredExecution;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/// {@link ExecutionSink} that routes all output through the daemon's broadcast infrastructure.
///
/// Wraps {@link BroadcastOutputStream} in a {@link PrintStream} so that
/// {@link io.hensu.core.execution.ExecutionListener} implementations (which accept a
/// {@code PrintStream}) and {@code WorkflowRunCommand}'s direct print calls write their
/// output to both the ring buffer (for replay) and all live subscriber queues (for streaming).
///
/// ANSI color codes are preserved byte-for-byte — the client decodes and writes them
/// directly to its terminal.
///
/// @implNote The underlying {@link BroadcastOutputStream} is synchronized; the
/// {@link PrintStream} wrapper adds {@code autoFlush=true} so each {@code println}
/// is flushed immediately.
///
/// @see BroadcastOutputStream
/// @see LocalExecutionSink
public final class DaemonExecutionSink implements ExecutionSink {

    private final PrintStream broadcastStream;

    /// Creates a sink that broadcasts execution output for the given stored execution.
    ///
    /// @param execution target execution; output is buffered and broadcast, not null
    /// @param mapper    Jackson mapper for frame serialization, not null
    public DaemonExecutionSink(StoredExecution execution, ObjectMapper mapper) {
        this.broadcastStream =
                new PrintStream(
                        new BroadcastOutputStream(execution, mapper),
                        true, // autoFlush — each println flushes immediately
                        StandardCharsets.UTF_8);
    }

    /// Returns the broadcast-backed output stream.
    ///
    /// @return print stream writing to the ring buffer and all subscribers, never null
    @Override
    public PrintStream out() {
        return broadcastStream;
    }

    /// Returns the broadcast-backed error stream.
    ///
    /// Errors are sent through the same broadcast channel so clients receive them
    /// inline with normal output.
    ///
    /// @return print stream writing to the ring buffer and all subscribers, never null
    @Override
    public PrintStream err() {
        return broadcastStream;
    }
}
