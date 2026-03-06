package io.hensu.cli.daemon;

import java.util.Arrays;

/// Circular byte buffer storing the most recent N bytes of execution output.
///
/// Enables re-attaching clients to receive buffered output from a running or
/// completed execution without replaying the entire output from the start.
/// When the buffer is full, the oldest bytes are silently overwritten.
///
/// ### Contracts
/// - **Precondition**: {@code capacity > 0}
/// - **Postcondition**: {@link #drain()} always returns bytes in write order
/// - **Invariant**: at most {@code capacity} bytes are retained at any time
///
/// ### Performance
/// - Time: O(n) per write (where n = bytes written), O(capacity) per drain
/// - Space: O(capacity) — fixed allocation, never grows
///
/// @implNote **Thread-safe**. All public methods are {@code synchronized}.
/// Safe for concurrent producer (execution virtual thread) and consumer
/// (socket reader virtual thread).
///
/// @see StoredExecution
public final class OutputRingBuffer {

    /// Default buffer capacity: 64 KB — enough for roughly 500 lines of output.
    public static final int DEFAULT_CAPACITY = 64 * 1024;

    private final byte[] buf;
    private int writePos = 0;
    private long totalWritten = 0;
    private boolean wrapped = false;

    /// Creates a buffer with {@link #DEFAULT_CAPACITY}.
    public OutputRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    /// Creates a buffer with the specified capacity.
    ///
    /// @param capacity maximum bytes retained, must be positive
    /// @throws IllegalArgumentException if {@code capacity <= 0}
    public OutputRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
        }
        this.buf = new byte[capacity];
    }

    /// Appends bytes to the buffer, overwriting the oldest bytes when full.
    ///
    /// @param bytes bytes to write, not null
    public synchronized void write(byte[] bytes) {
        int len = bytes.length;
        if (len == 0) {
            return;
        }
        totalWritten += len;

        if (len >= buf.length) {
            // Entire buffer will be overwritten — only keep the last buf.length bytes
            System.arraycopy(bytes, len - buf.length, buf, 0, buf.length);
            writePos = 0;
            wrapped = true;
            return;
        }

        int remaining = buf.length - writePos;
        if (len <= remaining) {
            System.arraycopy(bytes, 0, buf, writePos, len);
            writePos += len;
            if (writePos == buf.length) {
                writePos = 0;
                wrapped = true;
            }
        } else {
            // Split across the end boundary
            System.arraycopy(bytes, 0, buf, writePos, remaining);
            int leftover = len - remaining;
            System.arraycopy(bytes, remaining, buf, 0, leftover);
            writePos = leftover;
            wrapped = true;
        }
    }

    /// Returns a snapshot of all buffered bytes in write order.
    ///
    /// @return snapshot with bytes in chronological order and truncation flag, never null
    public synchronized ReplayResult drain() {
        if (!wrapped) {
            return new ReplayResult(Arrays.copyOf(buf, writePos), false);
        }
        // Reconstruct order: [writePos..end] + [0..writePos]
        byte[] result = new byte[buf.length];
        int tailLen = buf.length - writePos;
        System.arraycopy(buf, writePos, result, 0, tailLen);
        System.arraycopy(buf, 0, result, tailLen, writePos);
        return new ReplayResult(result, true);
    }

    /// Returns the total number of bytes ever written, including overwritten bytes.
    ///
    /// @return total bytes written since construction, always {@code >= 0}
    public synchronized long getTotalWritten() {
        return totalWritten;
    }

    // — Inner types ——————————————————————————————————————————————————————————

    /// Snapshot of buffered output for replay to a re-attaching client.
    ///
    /// @param bytes   buffered bytes in chronological order, never null
    /// @param truncated {@code true} if the ring buffer wrapped, meaning the replay
    ///                  does not include the complete execution output from the start
    public record ReplayResult(byte[] bytes, boolean truncated) {}
}
