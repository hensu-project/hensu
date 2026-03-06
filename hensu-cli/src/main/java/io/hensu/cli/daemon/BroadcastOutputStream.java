package io.hensu.cli.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;

/// {@link OutputStream} that simultaneously writes execution output to the ring
/// buffer and broadcasts it as {@code out} frames to all live subscribers.
///
/// Intended to be wrapped in a {@link java.io.PrintStream} and injected wherever
/// a {@code WorkflowRunCommand} or {@link io.hensu.core.execution.ExecutionListener}
/// would normally write to {@code System.out}.
///
/// ### Data Flow
/// ```
/// VerboseExecutionListener.out.println(...)
///         │
///         V
/// BroadcastOutputStream.write(byte[], int, int)
///         ├————> StoredExecution.outputBuffer.write(bytes)   [ring buffer, for replay]
///         └————> StoredExecution.broadcast(outFrame)         [live subscribers]
/// ```
///
/// ### Protocol
/// Raw bytes are base64-encoded and wrapped in an {@code out} NDJSON frame before
/// delivery to subscribers. ANSI escape codes in the byte array survive unchanged —
/// the client decodes base64 and writes the raw bytes directly to its terminal.
///
/// @implNote **Thread-safe**. {@code write} is synchronized to prevent interleaved
/// frames from concurrent callers (e.g. parallel node executors).
///
/// @see StoredExecution#broadcast(String)
/// @see OutputRingBuffer
public final class BroadcastOutputStream extends OutputStream {

    private final StoredExecution execution;
    private final ObjectMapper mapper;

    /// Creates a broadcast stream for the given execution.
    ///
    /// @param execution the target execution; output is buffered and broadcast, not null
    /// @param mapper    Jackson mapper used to serialize {@code out} frames, not null
    public BroadcastOutputStream(StoredExecution execution, ObjectMapper mapper) {
        this.execution = execution;
        this.mapper = mapper;
    }

    /// Writes a single byte. Delegates to {@link #write(byte[], int, int)}.
    ///
    /// @param b the byte to write
    @Override
    public void write(int b) {
        write(new byte[] {(byte) b}, 0, 1);
    }

    /// Intercepts a byte range, buffers it, and broadcasts it as an {@code out} frame.
    ///
    /// @param buf the byte array, not null
    /// @param off starting offset, must be valid
    /// @param len number of bytes to write, must be {@code >= 0}
    @Override
    public synchronized void write(byte[] buf, int off, int len) {
        if (len == 0) {
            return;
        }
        byte[] bytes = new byte[len];
        System.arraycopy(buf, off, bytes, 0, len);

        // 1. Persist in ring buffer for replay on re-attach
        execution.getOutputBuffer().write(bytes);

        // 2. Encode and broadcast to all live subscribers
        String b64 = Base64.getEncoder().encodeToString(bytes);
        try {
            String frame = mapper.writeValueAsString(DaemonFrame.out(execution.getId(), b64));
            execution.broadcast(frame);
        } catch (IOException e) {
            // Serialization of a simple POJO should never fail — log and continue
            System.err.println("[hensu-daemon] Failed to serialize out frame: " + e.getMessage());
        }
    }
}
