package io.hensu.cli.daemon;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/// Wire-protocol frame for daemon ↔ client communication over the Unix domain socket.
///
/// All messages in both directions are serialized as a single JSON line (NDJSON).
/// Unknown fields are ignored on deserialization; null fields are omitted on
/// serialization ({@link JsonInclude.Include#NON_NULL}).
///
/// ### Type Discriminator ({@code t} field)
///
/// **Client → Daemon:**
/// ```
/// run      — Start a new workflow execution
/// attach   — Re-attach to a running or completed execution
/// detach   — Disconnect without cancelling (Ctrl+C)
/// cancel   — Cancel a running execution
/// ps       — List all tracked executions
/// ping     — Health check
/// stop     — Graceful daemon shutdown
/// ```
///
/// **Daemon → Client:**
/// ```
/// pong          — Response to ping
/// exec_start    — Execution has begun
/// node_start    — A workflow node has started
/// node_end      — A workflow node has completed
/// out           — Raw execution output bytes (base64 in {@code b})
/// replay_start  — Start of buffered output replay on re-attach
/// replay_end    — End of buffered replay; live stream follows
/// exec_end      — Execution reached a terminal state
/// error         — Error; fatal=true means connection will close
/// daemon_full   — Daemon at max-concurrent capacity; request rejected
/// ps_response   — Response to list request
/// ```
///
/// ### Contracts
/// - **Precondition**: `type` must be set before serialization; all other fields are optional.
/// - **Postcondition**: Null fields are absent from the serialized JSON line.
/// - **Invariant**: Each frame is a self-contained JSON object terminated by `\n`.
///
/// @implNote **Not thread-safe.** Designed as a single-use data transfer object.
/// Do not share instances between threads without external synchronization.
///
/// @implNote Runs on JVM only — {@code hensu-cli} depends on {@code kotlin-compiler-embeddable}
/// which is incompatible with GraalVM native image. Standard Jackson reflection applies.
///
/// @see DaemonServer
/// @see DaemonClient
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DaemonFrame {

    // — Discriminator ———————————————————————————————————————————————————————

    /// Message type — present on every frame, never null.
    @JsonProperty("t")
    public String type;

    // — Identity ————————————————————————————————————————————————————————————

    /// Execution ID — present on all execution-scoped frames.
    @JsonProperty("id")
    public String execId;

    /// Workflow ID or name.
    @JsonProperty("wf")
    public String workflowId;

    // — Node tracking ———————————————————————————————————————————————————————

    /// Node ID for node_start / node_end frames.
    @JsonProperty("node")
    public String nodeId;

    /// Node type (e.g. "StandardNode") for node_start frames.
    @JsonProperty("node_type")
    public String nodeType;

    // — Status / result —————————————————————————————————————————————————————

    /// Execution or node status string (e.g. "SUCCESS", "FAILED").
    @JsonProperty("status")
    public String status;

    /// Duration in milliseconds for node_end frames.
    @JsonProperty("ms")
    public Long durationMs;

    /// Epoch-millisecond timestamp for exec_start frames.
    @JsonProperty("ts")
    public Long timestamp;

    // — Output ——————————————————————————————————————————————————————————————

    /// Base64-encoded raw output bytes for {@code out} and replay frames.
    @JsonProperty("b")
    public String bytes;

    /// {@code true} if the ring buffer wrapped and early output was lost (replay_start).
    @JsonProperty("truncated")
    public Boolean truncated;

    /// Bytes lost due to ring-buffer wrap (replay_start when truncated=true).
    @JsonProperty("bytes_lost")
    public Long bytesLost;

    // — Error ———————————————————————————————————————————————————————————————

    /// Human-readable error or status message.
    @JsonProperty("msg")
    public String message;

    /// {@code true} if the error is unrecoverable and the connection will close.
    @JsonProperty("fatal")
    public Boolean fatal;

    // — Capacity ————————————————————————————————————————————————————————————

    /// Max-concurrent limit (daemon_full frames).
    @JsonProperty("max")
    public Integer maxConcurrent;

    // — Run request payload ————————————————————————————————————————————————

    /// Pre-compiled workflow JSON (run frames).
    @JsonProperty("workflow_json")
    public String workflowJson;

    /// Initial context map (run frames).
    @JsonProperty("context")
    public Map<String, Object> context;

    /// Whether to stream verbose agent I/O (run frames).
    @JsonProperty("verbose")
    public Boolean verbose;

    /// Whether to apply ANSI color codes (run / attach frames).
    @JsonProperty("color")
    public Boolean color;

    /// Client terminal width in columns for separator sizing (run / attach frames).
    @JsonProperty("term_width")
    public Integer termWidth;

    // — List response ———————————————————————————————————————————————————————

    /// Execution summaries for ps_response frames.
    @JsonProperty("executions")
    public List<PsEntry> executions;

    // — Factory helpers ————————————————————————————————————————————————————

    /// Creates a {@code pong} response frame.
    ///
    /// @return pong frame, never null
    public static DaemonFrame pong() {
        var f = new DaemonFrame();
        f.type = "pong";
        return f;
    }

    /// Creates an {@code exec_start} frame.
    ///
    /// @param execId     execution identifier, not null
    /// @param workflowId workflow name/id, not null
    /// @return frame, never null
    public static DaemonFrame execStart(String execId, String workflowId) {
        var f = new DaemonFrame();
        f.type = "exec_start";
        f.execId = execId;
        f.workflowId = workflowId;
        f.timestamp = System.currentTimeMillis();
        return f;
    }

    /// Creates a {@code node_start} frame.
    ///
    /// @param execId   execution identifier, not null
    /// @param nodeId   node identifier, not null
    /// @param nodeType node type class name, not null
    /// @return frame, never null
    public static DaemonFrame nodeStart(String execId, String nodeId, String nodeType) {
        var f = new DaemonFrame();
        f.type = "node_start";
        f.execId = execId;
        f.nodeId = nodeId;
        f.nodeType = nodeType;
        return f;
    }

    /// Creates a {@code node_end} frame.
    ///
    /// @param execId     execution identifier, not null
    /// @param nodeId     node identifier, not null
    /// @param status     node exit status, not null
    /// @param durationMs node execution duration in milliseconds
    /// @return frame, never null
    public static DaemonFrame nodeEnd(
            String execId, String nodeId, String status, long durationMs) {
        var f = new DaemonFrame();
        f.type = "node_end";
        f.execId = execId;
        f.nodeId = nodeId;
        f.status = status;
        f.durationMs = durationMs;
        return f;
    }

    /// Creates an {@code out} frame carrying raw output bytes.
    ///
    /// @param execId      execution identifier, not null
    /// @param base64bytes base64-encoded raw bytes, not null
    /// @return frame, never null
    public static DaemonFrame out(String execId, String base64bytes) {
        var f = new DaemonFrame();
        f.type = "out";
        f.execId = execId;
        f.bytes = base64bytes;
        return f;
    }

    /// Creates a {@code replay_start} frame.
    ///
    /// @param execId    execution identifier, not null
    /// @param truncated {@code true} if ring buffer wrapped and early output was lost
    /// @param bytesLost bytes lost due to wrap; ignored when not truncated
    /// @return frame, never null
    public static DaemonFrame replayStart(String execId, boolean truncated, long bytesLost) {
        var f = new DaemonFrame();
        f.type = "replay_start";
        f.execId = execId;
        f.truncated = truncated;
        f.bytesLost = truncated ? bytesLost : null;
        return f;
    }

    /// Creates a {@code replay_end} frame.
    ///
    /// @param execId execution identifier, not null
    /// @return frame, never null
    public static DaemonFrame replayEnd(String execId) {
        var f = new DaemonFrame();
        f.type = "replay_end";
        f.execId = execId;
        return f;
    }

    /// Creates an {@code exec_end} frame.
    ///
    /// @param execId execution identifier, not null
    /// @param status terminal status string, not null
    /// @return frame, never null
    public static DaemonFrame execEnd(String execId, String status) {
        var f = new DaemonFrame();
        f.type = "exec_end";
        f.execId = execId;
        f.status = status;
        return f;
    }

    /// Creates an {@code error} frame.
    ///
    /// @param execId  execution identifier, may be null for daemon-level errors
    /// @param message error description, not null
    /// @param fatal   {@code true} if the error closes the connection
    /// @return frame, never null
    public static DaemonFrame error(String execId, String message, boolean fatal) {
        var f = new DaemonFrame();
        f.type = "error";
        f.execId = execId;
        f.message = message;
        f.fatal = fatal;
        return f;
    }

    /// Creates a {@code daemon_full} frame.
    ///
    /// @param maxConcurrent the configured max-concurrent-executions limit
    /// @return frame, never null
    public static DaemonFrame daemonFull(int maxConcurrent) {
        var f = new DaemonFrame();
        f.type = "daemon_full";
        f.maxConcurrent = maxConcurrent;
        return f;
    }

    /// Creates a {@code ps_response} frame.
    ///
    /// @param executions list of execution summaries, not null
    /// @return frame, never null
    public static DaemonFrame psResponse(List<PsEntry> executions) {
        var f = new DaemonFrame();
        f.type = "ps_response";
        f.executions = executions;
        return f;
    }

    // — Nested types ————————————————————————————————————————————————————————

    /// Summary of a single execution for {@code ps_response} frames.
    ///
    /// @param execId      execution identifier, not null
    /// @param workflowId  workflow name/id, not null
    /// @param status      current status string, not null
    /// @param currentNode node currently executing, may be null
    /// @param elapsedMs   milliseconds since execution started
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PsEntry(
            @JsonProperty("id") String execId,
            @JsonProperty("wf") String workflowId,
            @JsonProperty("status") String status,
            @JsonProperty("node") String currentNode,
            @JsonProperty("elapsed_ms") long elapsedMs) {}
}
