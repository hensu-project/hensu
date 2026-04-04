package io.hensu.cli.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.hensu.cli.execution.DaemonExecutionSink;
import io.hensu.cli.execution.VerboseExecutionListenerFactory;
import io.hensu.cli.review.DaemonReviewHandler;
import io.hensu.core.HensuEnvironment;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.review.ReviewDecision;
import io.hensu.serialization.WorkflowSerializer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import sun.misc.Signal;

/// Unix domain socket server that manages daemon-mode workflow executions.
///
/// Listens on {@link DaemonPaths#socket()} for NDJSON-framed requests from the
/// Hensu CLI client ({@link DaemonClient}). Each incoming connection is handled
/// on a dedicated virtual thread. Workflow executions run on their own virtual
/// threads sharing the singleton {@link HensuEnvironment} — matching the pattern
/// used by {@code hensu-server}'s {@code WorkflowService}.
///
/// ### Concurrency Model
/// Concurrency mirrors the server: one virtual thread per workflow, shared stateless
/// {@link io.hensu.core.execution.WorkflowExecutor}. The executor is safe for
/// concurrent use because all mutable state lives in per-call
/// {@link io.hensu.core.state.HensuState} objects.
///
/// ### Output Delivery
/// Each execution is assigned a {@link StoredExecution} with an {@link OutputRingBuffer}.
/// A {@link DaemonExecutionSink} backed by a {@link BroadcastOutputStream} intercepts all
/// {@code PrintStream} writes, stores them in the ring buffer, and fans them out as base64
/// {@code out} frames to all connected subscriber queues.
///
/// ### Socket Activation
/// When started under systemd socket activation, {@link System#inheritedChannel()} returns
/// the pre-bound {@link ServerSocketChannel} created by the {@code hensu-daemon.socket} unit.
/// The daemon uses it directly without rebinding, and the stale-socket cleanup step is skipped.
/// When there is no inherited channel (background child, {@code --foreground} direct start),
/// the daemon binds the Unix socket itself.
///
/// ### Signal Handling
/// {@code SIGINT} (Ctrl+C from a client terminal) is explicitly ignored — the daemon
/// outlives its clients. Only {@code SIGTERM} (or an explicit {@code stop} frame)
/// triggers a graceful shutdown.
///
/// @implNote **Thread-safe**. The {@link ExecutionStore} uses a
/// {@link java.util.concurrent.ConcurrentHashMap};
/// per-execution state transitions are synchronized on {@link StoredExecution}.
///
/// @see DaemonClient
/// @see ExecutionStore
/// @see StoredExecution
@ApplicationScoped
public class DaemonServer {

    private static final Logger log = Logger.getLogger(DaemonServer.class.getName());
    private static final int SUBSCRIBER_QUEUE_CAPACITY = 200;

    @Inject HensuEnvironment environment;
    @Inject VerboseExecutionListenerFactory listenerFactory;
    @Inject DaemonReviewHandler daemonReviewHandler;

    private final ExecutionStore store = new ExecutionStore();
    private final ObjectMapper mapper =
            WorkflowSerializer.createMapper().disable(SerializationFeature.INDENT_OUTPUT);

    private volatile boolean running = false;
    private ServerSocketChannel serverChannel;

    // — Lifecycle ————————————————————————————————————————————————————————————

    /// Starts the daemon: binds the Unix socket, writes the PID file, then blocks
    /// in the accept loop until {@link #stop()} is called or a {@code stop} frame arrives.
    ///
    /// @apiNote **Side effects**:
    /// - Creates {@code ~/.hensu/} directory if absent
    /// - Deletes stale {@code daemon.sock} if not socket-activated (background child path only)
    /// - Writes current PID to {@code daemon.pid}
    /// - Ignores {@code SIGINT} for the lifetime of this call
    ///
    /// @throws IOException if the socket cannot be bound
    public void start() throws IOException {
        Files.createDirectories(DaemonPaths.base());

        Channel inherited = System.inheritedChannel();
        if (inherited instanceof ServerSocketChannel activatedChannel) {
            // systemd socket activation: socket already bound — use it directly
            serverChannel = activatedChannel;
            log.info("Daemon activated via systemd socket");
        } else {
            // Manual startup (background child, --foreground direct)
            Files.deleteIfExists(DaemonPaths.socket());
            serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            serverChannel.bind(UnixDomainSocketAddress.of(DaemonPaths.socket()));
            log.info("Daemon listening on " + DaemonPaths.socket());
        }

        writePid();
        store.start();
        running = true;
        sdNotifyReady();

        // Ignore Ctrl+C — daemon outlives its clients
        Signal.handle(new Signal("INT"), _ -> log.fine("SIGINT ignored by daemon"));
        // JVM shutdown hook handles SIGTERM reliably.
        // sun.misc.Signal("TERM") is pre-empted by Quarkus/Vert.x which converts SIGTERM to
        // System.exit() internally — that path runs shutdown hooks, not signal handlers.
        // The hook also calls cleanup() to guarantee socket/PID removal even if the JVM halts
        // before the main thread reaches the finally block.
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    log.fine("JVM shutdown — stopping daemon");
                                    stop();
                                    cleanup();
                                },
                                "hensu-shutdown"));

        try {
            while (running) {
                try {
                    SocketChannel client = serverChannel.accept();
                    Thread.ofVirtual()
                            .name("hensu-conn-" + UUID.randomUUID().toString().substring(0, 8))
                            .start(() -> handleConnection(client));
                } catch (ClosedChannelException e) {
                    // Channel closed by stop() — normal shutdown, not an error
                    break;
                }
            }
        } finally {
            cleanup();
        }
    }

    /// Stops the accept loop and releases the socket.
    ///
    /// @apiNote **Side effects**: closes server socket, deletes pid and socket files.
    public void stop() {
        running = false;
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException e) {
            log.warning("Error closing server channel: " + e.getMessage());
        }
    }

    // — Connection handling ——————————————————————————————————————————————————

    private void handleConnection(SocketChannel channel) {
        try (channel) {
            var reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    Channels.newInputStream(channel), StandardCharsets.UTF_8));
            var writer =
                    new PrintWriter(
                            Channels.newOutputStream(channel), true, StandardCharsets.UTF_8);

            String line = reader.readLine();
            if (line == null) return;

            DaemonFrame req = mapper.readValue(line, DaemonFrame.class);
            switch (req.type) {
                case "run" -> handleRun(req, reader, writer);
                case "attach" -> handleAttach(req, reader, writer);
                case "cancel" -> handleCancel(req, writer);
                case "list" -> handleList(writer);
                case "ping" -> write(writer, DaemonFrame.pong());
                case "stop" -> {
                    write(writer, DaemonFrame.pong());
                    stop();
                }
                default ->
                        write(
                                writer,
                                DaemonFrame.error(null, "Unknown frame type: " + req.type, true));
            }
        } catch (Exception e) {
            log.fine("Connection closed: " + e.getMessage());
        }
    }

    // — Run ——————————————————————————————————————————————————————————————————

    private void handleRun(DaemonFrame req, BufferedReader clientReader, PrintWriter writer) {
        String execId = req.execId != null ? req.execId : UUID.randomUUID().toString();

        if (req.workflowJson == null || req.workflowJson.isBlank()) {
            write(writer, DaemonFrame.error(execId, "Missing workflow_json", true));
            return;
        }

        var execution =
                new StoredExecution(execId, req.workflowId != null ? req.workflowId : execId);
        store.register(execution);

        var outputQueue = new LinkedBlockingQueue<String>(SUBSCRIBER_QUEUE_CAPACITY);
        execution.addSubscriber(outputQueue);

        int termWidth = req.termWidth != null ? req.termWidth : 80;
        boolean useColor = req.color == null || req.color;
        boolean verbose = Boolean.TRUE.equals(req.verbose);
        boolean interactive = Boolean.TRUE.equals(req.interactive);

        if (interactive) {
            // Register the execution so review checkpoints route back through this socket.
            // The frame sender enqueues serialized JSON directly into the output queue so
            // review_request frames are delivered in the same ordered stream as other output.
            Consumer<DaemonFrame> frameSender = frame -> outputQueue.offer(safeSerialize(frame));
            daemonReviewHandler.registerExecution(execId, frameSender, execution::updateStatus);

            // Reader virtual thread: handles review_response, cancel, and detach frames
            // that arrive from the client while the drain loop is writing. This runs
            // concurrently with drainQueueToWriter — no shared state except clientReader.
            Thread.ofVirtual()
                    .name("hensu-review-reader-" + execId.substring(0, 8))
                    .start(() -> readClientFrames(clientReader, execId));
        }

        Thread.ofVirtual()
                .name("hensu-exec-" + execId.substring(0, 8))
                .start(
                        () -> {
                            try {
                                runExecution(execution, req, useColor, verbose, termWidth);
                            } finally {
                                if (interactive) {
                                    daemonReviewHandler.unregisterExecution(execId);
                                }
                            }
                        });

        write(writer, DaemonFrame.execStart(execId, execution.getWorkflowId()));

        // Drain output queue → write to client socket
        drainQueueToWriter(outputQueue, writer, execId);

        // Non-interactive: read any remaining client command (detach/cancel) after drain.
        // Interactive: the reader thread above handles these concurrently during execution.
        if (!interactive) {
            consumeClientCommands(clientReader, execId);
        }
    }

    // — Attach ——————————————————————————————————————————————————————————————

    private void handleAttach(DaemonFrame req, BufferedReader clientReader, PrintWriter writer) {
        if (req.execId == null) {
            write(writer, DaemonFrame.error(null, "Missing id", true));
            return;
        }

        StoredExecution execution = store.get(req.execId);
        if (execution == null) {
            write(
                    writer,
                    DaemonFrame.error(req.execId, "Execution not found: " + req.execId, true));
            return;
        }

        // Replay buffered output first
        OutputRingBuffer.ReplayResult replay = execution.getOutputBuffer().drain();
        write(
                writer,
                DaemonFrame.replayStart(
                        req.execId,
                        replay.truncated(),
                        replay.truncated()
                                ? execution.getOutputBuffer().getTotalWritten()
                                        - replay.bytes().length
                                : 0));

        if (replay.bytes().length > 0) {
            String b64 = Base64.getEncoder().encodeToString(replay.bytes());
            write(writer, DaemonFrame.out(req.execId, b64));
        }
        write(writer, DaemonFrame.replayEnd(req.execId));

        // If already terminal, send final frame and close
        if (execution.getStatus().isTerminal()) {
            write(writer, DaemonFrame.execEnd(req.execId, execution.getStatus().name()));
            return;
        }

        // Still running — subscribe for live output
        var outputQueue = new LinkedBlockingQueue<String>(SUBSCRIBER_QUEUE_CAPACITY);
        execution.addSubscriber(outputQueue);

        // If the execution is interactive, re-register the new queue as the frame sender
        // so pending review_request frames are re-delivered to this client, and start a
        // reader thread so the client can send review_response / detach frames back.
        if (daemonReviewHandler.isInteractive(req.execId)) {
            Consumer<DaemonFrame> frameSender = frame -> outputQueue.offer(safeSerialize(frame));
            daemonReviewHandler.resumeExecution(req.execId, frameSender);
            Thread.ofVirtual()
                    .name("hensu-review-reader-" + req.execId.substring(0, 8))
                    .start(() -> readClientFrames(clientReader, req.execId));
        }

        drainQueueToWriter(outputQueue, writer, req.execId);
    }

    // — Cancel ——————————————————————————————————————————————————————————————

    private void handleCancel(DaemonFrame req, PrintWriter writer) {
        if (req.execId == null) {
            write(writer, DaemonFrame.error(null, "Missing id", true));
            return;
        }
        StoredExecution execution = store.get(req.execId);
        if (execution == null || execution.getStatus().isTerminal()) {
            write(
                    writer,
                    DaemonFrame.error(
                            req.execId, "Execution not found or already finished", false));
            return;
        }
        // The virtual thread running this execution will be interrupted on next blocking call
        String cancelFrame;
        try {
            cancelFrame = mapper.writeValueAsString(DaemonFrame.execEnd(req.execId, "CANCELLED"));
        } catch (Exception e) {
            cancelFrame =
                    "{\"t\":\"exec_end\",\"id\":\"" + req.execId + "\",\"status\":\"CANCELLED\"}";
        }
        execution.markCancelled(cancelFrame);
        write(writer, DaemonFrame.pong());
    }

    // — List ————————————————————————————————————————————————————————————————

    private void handleList(PrintWriter writer) {
        List<DaemonFrame.PsEntry> entries =
                store.all().stream()
                        .map(
                                e ->
                                        new DaemonFrame.PsEntry(
                                                e.getId(),
                                                e.getWorkflowId(),
                                                e.getStatus().name(),
                                                e.getCurrentNode(),
                                                e.getElapsedMs()))
                        .toList();
        write(writer, DaemonFrame.psResponse(entries));
    }

    // — Execution runner ————————————————————————————————————————————————————

    private void runExecution(
            StoredExecution execution,
            DaemonFrame req,
            boolean useColor,
            boolean verbose,
            int termWidth) {
        String execId = execution.getId();
        try {
            var workflow = WorkflowSerializer.fromJson(req.workflowJson);
            var context =
                    req.context != null
                            ? new HashMap<>(req.context)
                            : new HashMap<String, Object>();
            // Align the engine's internal executionId with the daemon's tracking ID.
            // Without this, WorkflowExecutor generates its own UUID and
            // DaemonReviewManager cannot correlate the review to this execution.
            context.put("_execution_id", execId);

            var sink = new DaemonExecutionSink(execution, mapper);
            ExecutionListener listener =
                    verbose
                            ? listenerFactory.create(workflow, sink.out(), useColor, termWidth)
                            : ExecutionListener.NOOP;

            execution.markRunning(workflow.getStartNode());

            ExecutionResult result =
                    environment.getWorkflowExecutor().execute(workflow, context, listener);

            String finalFrame =
                    mapper.writeValueAsString(DaemonFrame.execEnd(execId, extractStatus(result)));
            execution.markCompleted(result, finalFrame);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tryMarkCancelled(execution, execId);
        } catch (Throwable t) {
            log.warning("Execution " + execId + " failed: " + t.getMessage());
            String errFrame = safeSerialize(DaemonFrame.error(execId, t.getMessage(), true));
            execution.markFailed(t.getMessage(), errFrame);
        }
    }

    // — Queue drain ————————————————————————————————————————————————————————

    private void drainQueueToWriter(
            LinkedBlockingQueue<String> queue, PrintWriter writer, String execId) {
        try {
            while (true) {
                String frame = queue.poll(30, TimeUnit.SECONDS);
                if (frame == null) {
                    // Heartbeat timeout — write a ping to detect dead connections
                    writer.println("{\"t\":\"ping\"}");
                    if (writer.checkError()) break;
                    continue;
                }
                if (StoredExecution.poisonPill().equals(frame)) {
                    break;
                }
                writer.println(frame);
                if (writer.checkError()) {
                    log.fine("Client disconnected from execution " + execId);
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // — Interactive client frame reader ————————————————————————————————————

    /// Reads incoming frames from the client during an interactive execution.
    ///
    /// Runs on its own virtual thread alongside {@link #drainQueueToWriter}.
    /// Routes {@code review_response} frames to {@link DaemonReviewHandler} and
    /// handles {@code cancel} / {@code detach} frames. Exits when the socket closes
    /// (which happens naturally when the connection handler's try-with-resources closes
    /// the channel after {@link #drainQueueToWriter} returns).
    ///
    /// @param reader  client input stream, not null
    /// @param execId  execution identifier for cancel/review routing, not null
    private void readClientFrames(BufferedReader reader, String execId) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                DaemonFrame frame = mapper.readValue(line, DaemonFrame.class);
                switch (frame.type) {
                    case "review_response" -> {
                        if (frame.reviewId != null) {
                            daemonReviewHandler.completeReview(
                                    frame.reviewId, parseReviewDecision(frame));
                        }
                    }
                    case "cancel" -> {
                        StoredExecution exec = store.get(execId);
                        if (exec != null && !exec.getStatus().isTerminal()) {
                            exec.markCancelled(
                                    safeSerialize(DaemonFrame.execEnd(execId, "CANCELLED")));
                        }
                        return;
                    }
                    case "detach" -> {
                        return;
                    }
                    default -> {
                        /* ignore unknown frames */
                    }
                }
            }
        } catch (Exception ignored) {
            // Socket closed — normal shutdown
        } finally {
            // Client disconnected — suspend rather than cancel so the execution stays
            // paused at the review checkpoint. A reattaching client can resume the review.
            daemonReviewHandler.suspendExecution(execId);
        }
    }

    /// Converts a {@code review_response} frame into a {@link ReviewDecision}.
    ///
    /// @param frame a frame with {@code type="review_response"}, not null
    /// @return the corresponding decision; defaults to {@link ReviewDecision.Approve} for
    ///         unknown decision strings
    private ReviewDecision parseReviewDecision(DaemonFrame frame) {
        return switch (frame.decision != null ? frame.decision : "approve") {
            case "reject" ->
                    new ReviewDecision.Reject(
                            frame.backtrackReason != null
                                    ? frame.backtrackReason
                                    : "Rejected by reviewer");
            case "backtrack" ->
                    new ReviewDecision.Backtrack(
                            frame.backtrackNodeId,
                            frame.editedContext,
                            frame.backtrackReason != null
                                    ? frame.backtrackReason
                                    : "Manual backtrack");
            default -> new ReviewDecision.Approve(null);
        };
    }

    // — Client command reader ——————————————————————————————————————————————

    private void consumeClientCommands(BufferedReader reader, String execId) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                DaemonFrame frame = mapper.readValue(line, DaemonFrame.class);
                if ("cancel".equals(frame.type)) {
                    StoredExecution exec = store.get(execId);
                    if (exec != null && !exec.getStatus().isTerminal()) {
                        String cancelFrame =
                                safeSerialize(DaemonFrame.execEnd(execId, "CANCELLED"));
                        exec.markCancelled(cancelFrame);
                    }
                    break;
                }
                if ("detach".equals(frame.type)) break;
            }
        } catch (Exception ignored) {
            // Client disconnected — no action needed
        }
    }

    // — Helpers ———————————————————————————————————————————————————————————

    private void write(PrintWriter writer, DaemonFrame frame) {
        try {
            writer.println(mapper.writeValueAsString(frame));
        } catch (Exception e) {
            log.warning("Failed to write frame: " + e.getMessage());
        }
    }

    private String safeSerialize(DaemonFrame frame) {
        try {
            return mapper.writeValueAsString(frame);
        } catch (Exception e) {
            return "{\"t\":\"error\",\"msg\":\"serialization failed\"}";
        }
    }

    private void tryMarkCancelled(StoredExecution execution, String execId) {
        String cancelFrame = safeSerialize(DaemonFrame.execEnd(execId, "CANCELLED"));
        execution.markCancelled(cancelFrame);
    }

    private String extractStatus(ExecutionResult result) {
        return switch (result) {
            case ExecutionResult.Completed c -> c.getExitStatus().name();
            case ExecutionResult.Success _ -> "SUCCESS";
            case ExecutionResult.Rejected _ -> "REJECTED";
            case ExecutionResult.Paused _ -> "PAUSED";
            case ExecutionResult.Failure _ -> "FAILURE";
        };
    }

    /// Sends {@code READY=1} to systemd once the daemon socket is bound and the
    /// accept loop is about to start.
    ///
    /// Java NIO does not implement {@code SOCK_DGRAM} for Unix domain sockets
    /// ({@code DatagramChannel.open(StandardProtocolFamily.UNIX)} throws
    /// {@code UnsupportedOperationException} even on JDK 25), so this method
    /// delegates to the {@code systemd-notify} binary that ships with every
    /// systemd installation. The service unit must include {@code NotifyAccess=all}
    /// because the child is a runtime fork of the JVM, not a systemd control process
    /// ({@code exec} only covers processes launched via {@code Exec*=} directives).
    ///
    /// **No-op on macOS / launchd / plain Linux**: {@code NOTIFY_SOCKET} is only
    /// set by systemd, so this method is entirely inert on non-systemd platforms.
    /// Failures are logged at WARNING and do not abort startup.
    private static void sdNotifyReady() {
        if (System.getenv("NOTIFY_SOCKET") == null) return;
        String notifyBin = SystemBinaries.systemdNotify();
        if (notifyBin == null) {
            log.warning("sd_notify: systemd-notify binary not found in trusted locations");
            return;
        }
        try {
            int exit =
                    new ProcessBuilder(notifyBin, "--ready")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start()
                            .waitFor();
            if (exit == 0) {
                log.info("sd_notify: READY=1 sent");
            } else {
                log.warning("sd_notify: systemd-notify exited " + exit);
            }
        } catch (Exception e) {
            log.warning(
                    "sd_notify failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void writePid() {
        try {
            Files.writeString(DaemonPaths.pidFile(), String.valueOf(ProcessHandle.current().pid()));
        } catch (IOException e) {
            log.warning("Could not write PID file: " + e.getMessage());
        }
    }

    private void cleanup() {
        store.stop();
        try {
            Files.deleteIfExists(DaemonPaths.socket());
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(DaemonPaths.pidFile());
        } catch (IOException ignored) {
        }
    }
}
