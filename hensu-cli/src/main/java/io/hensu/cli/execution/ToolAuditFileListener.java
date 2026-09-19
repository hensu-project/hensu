package io.hensu.cli.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.cli.daemon.DaemonPaths;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolResultEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/// Appends one JSON line per settled tool call to the CLI's audit log.
///
/// The CLI has no database, and the question the audit answers — what did this run
/// actually do to my machine — is asked most often exactly where there is no server: a
/// laptop that granted an agent a catalog of commands and left it running. A file that
/// outlives the process is the CLI's form of the same record the server keeps in
/// `runtime.tool_audit`, with the same contents and the same refusal to fail the run it
/// is recording.
///
/// Lines are JSON objects, one per line, appended. That shape is chosen so the file stays
/// readable with `tail` and greppable by tool name while still being parseable — an audit
/// nobody can read in the terminal where it was produced does not get read.
///
/// ### Contracts
/// - **Invariant**: a write failure is logged once and never propagates
/// - **Invariant**: arguments arrive already redacted; this class adds no values of its own
/// - **Invariant**: a call pairs with its outcome by call id, not by node and tool name,
///   so the row's arguments always belong to the call its outcome describes
///
/// @implNote Thread-safe. Appends are serialized on one lock because parallel fan-out
///     branches settle calls concurrently and interleaved partial lines would corrupt the
///     file.
/// @see ExecutionListener for the hooks this consumes
public final class ToolAuditFileListener implements ExecutionListener {

    private static final Logger logger = Logger.getLogger(ToolAuditFileListener.class.getName());

    /// Name of the audit file inside the CLI's data directory.
    public static final String FILE_NAME = "tool-audit.log";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ToolCallEvent> pending = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final Path file;
    private final String executionId;

    private volatile boolean reportedFailure;

    /// Creates a listener appending to the CLI's default audit file.
    ///
    /// @param executionId the execution being recorded, not null
    public ToolAuditFileListener(String executionId) {
        this(executionId, DaemonPaths.base().resolve(FILE_NAME));
    }

    /// Creates a listener appending to an explicit file.
    ///
    /// @param executionId the execution being recorded, not null
    /// @param file where lines are appended, not null
    /// @apiNote Test seam, so the trail can be asserted without touching the user's data
    ///     directory.
    public ToolAuditFileListener(String executionId, Path file) {
        this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
        this.file = Objects.requireNonNull(file, "file must not be null");
    }

    @Override
    public void onToolCall(ToolCallEvent event) {
        pending.put(event.callId(), event);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        ToolCallEvent call = pending.remove(event.callId());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("execution_id", executionId);
        row.put("call_id", event.callId());
        row.put("occurred_at", event.occurredAt().toString());
        row.put("node_id", event.nodeId());
        row.put("agent_id", event.agentId());
        row.put("tool", event.toolName());
        row.put("status", event.status().name());
        row.put("duration_ms", event.durationMs());
        row.put("exit_code", event.exitCode());
        row.put("argv", event.argv());
        row.put("arguments", call != null ? call.arguments() : Map.of());
        row.put("output", event.output());

        append(row);
    }

    private void append(Map<String, Object> row) {
        writeLock.lock();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    file,
                    mapper.writeValueAsString(row) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // Once, not per call: a full disk would otherwise turn one lost record into a
            // flood of warnings drowning the run's own output.
            if (!reportedFailure) {
                reportedFailure = true;
                logger.warning(
                        "Tool audit could not be written to " + file + ": " + e.getMessage());
            }
        } finally {
            writeLock.unlock();
        }
    }
}
