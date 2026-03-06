package io.hensu.cli.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.hensu.serialization.WorkflowSerializer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/// Thin client for communicating with a running {@link DaemonServer}.
///
/// Connects to {@link DaemonPaths#socket()}, sends a single NDJSON request frame,
/// and streams response frames back to the caller via a {@link Consumer}.
/// Each method opens a fresh connection, communicates, then closes it.
///
/// ### Usage — run a workflow via daemon
/// {@snippet :
/// if (DaemonClient.isAlive()) {
///     var client = new DaemonClient();
///     client.run(frame, f -> {
///         if ("out".equals(f.type)) System.out.write(Base64.getDecoder().decode(f.bytes));
///     });
/// }
/// }
///
/// @implNote Runs on JVM only — no native-image constraints apply to the CLI module.
///
/// @see DaemonServer
/// @see DaemonFrame
public final class DaemonClient {

    private static final ObjectMapper MAPPER =
            WorkflowSerializer.createMapper().disable(SerializationFeature.INDENT_OUTPUT);

    // — Static helpers ———————————————————————————————————————————————————————

    /// Returns {@code true} if a daemon is reachable at {@link DaemonPaths#socket()}.
    ///
    /// Sends a {@code ping} frame and expects a {@code pong} response within the
    /// connection timeout. Safe to call on every {@code hensu run} invocation.
    ///
    /// @return {@code true} if daemon responded to ping, {@code false} otherwise
    public static boolean isAlive() {
        if (!java.nio.file.Files.exists(DaemonPaths.socket())) {
            return false;
        }
        try {
            var client = new DaemonClient();
            DaemonFrame[] response = new DaemonFrame[1];
            var ping = new DaemonFrame();
            ping.type = "ping";
            client.send(ping, f -> response[0] = f);
            return response[0] != null && "pong".equals(response[0].type);
        } catch (Exception e) {
            return false;
        }
    }

    // — Public API ———————————————————————————————————————————————————————————

    /// Sends a {@code run} frame and streams response frames to {@code consumer} until
    /// the execution terminates or the connection closes.
    ///
    /// The consumer is called on the calling thread for each received frame. Typical
    /// callers print {@code out} frame bytes to the terminal and exit on {@code exec_end}.
    ///
    /// @param runFrame request frame with {@code type="run"}, not null
    /// @param consumer receives every response frame; called synchronously, not null
    /// @throws IOException if the socket connection fails
    public void run(DaemonFrame runFrame, Consumer<DaemonFrame> consumer) throws IOException {
        stream(runFrame, consumer);
    }

    /// Attaches to a running or completed execution and streams output to {@code consumer}.
    ///
    /// The daemon sends buffered replay output followed by live frames. The consumer
    /// receives {@code replay_start}, {@code out}, {@code replay_end}, then live {@code out}
    /// frames until {@code exec_end} or client detach.
    ///
    /// @param execId   execution identifier to attach to, not null
    /// @param consumer receives every response frame; called synchronously, not null
    /// @throws IOException if the socket connection fails
    public void attach(String execId, Consumer<DaemonFrame> consumer) throws IOException {
        var req = new DaemonFrame();
        req.type = "attach";
        req.execId = execId;
        stream(req, consumer);
    }

    /// Sends a detach signal for the given execution.
    ///
    /// Non-blocking: the execution continues running in the daemon after this call.
    /// Errors are silently swallowed — detach is best-effort (called from shutdown hooks).
    ///
    /// @param execId execution identifier, not null
    public void detach(String execId) {
        var req = new DaemonFrame();
        req.type = "detach";
        req.execId = execId;
        try {
            send(req, _ -> {});
        } catch (Exception ignored) {
        }
    }

    /// Sends a cancel request for the given execution.
    ///
    /// @param execId execution identifier, not null
    /// @throws IOException if the socket connection fails
    public void cancel(String execId) throws IOException {
        var req = new DaemonFrame();
        req.type = "cancel";
        req.execId = execId;
        send(req, _ -> {});
    }

    /// Lists all executions tracked by the daemon.
    ///
    /// @return list of execution summaries, never null, may be empty
    /// @throws IOException if the socket connection fails
    public List<DaemonFrame.PsEntry> list() throws IOException {
        var req = new DaemonFrame();
        req.type = "list";
        DaemonFrame[] response = new DaemonFrame[1];
        send(
                req,
                f -> {
                    if ("ps_response".equals(f.type)) {
                        response[0] = f;
                    }
                });
        if (response[0] != null && response[0].executions != null) {
            return response[0].executions;
        }
        return List.of();
    }

    /// Sends a stop request to gracefully shut down the daemon.
    ///
    /// @throws IOException if the socket connection fails
    public void stop() throws IOException {
        var req = new DaemonFrame();
        req.type = "stop";
        send(req, _ -> {});
    }

    // — Internal ——————————————————————————————————————————————————————————————

    /// Sends one frame and reads response frames until the connection closes or
    /// the stream ends.
    private void stream(DaemonFrame request, Consumer<DaemonFrame> consumer) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(DaemonPaths.socket()));

            var writer =
                    new PrintWriter(
                            Channels.newOutputStream(channel), true, StandardCharsets.UTF_8);
            var reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    Channels.newInputStream(channel), StandardCharsets.UTF_8));

            writer.println(MAPPER.writeValueAsString(request));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    DaemonFrame frame = MAPPER.readValue(line, DaemonFrame.class);
                    consumer.accept(frame);
                    // Stop reading after terminal frames
                    if ("exec_end".equals(frame.type)
                            || ("error".equals(frame.type) && Boolean.TRUE.equals(frame.fatal))
                            || "daemon_full".equals(frame.type)
                            || "ps_response".equals(frame.type)
                            || "pong".equals(frame.type)) {
                        break;
                    }
                } catch (Exception e) {
                    // Skip malformed lines
                }
            }
        }
    }

    /// Sends one frame and reads a single response.
    private void send(DaemonFrame request, Consumer<DaemonFrame> consumer) throws IOException {
        stream(request, consumer);
    }

    // — Output helpers ————————————————————————————————————————————————————————

    /// Decodes and writes raw bytes from an {@code out} frame directly to {@code System.out}.
    ///
    /// ANSI escape codes are preserved byte-for-byte.
    ///
    /// @param frame an {@code out} frame with base64 {@code b} field, not null
    public static void printOutFrame(DaemonFrame frame) {
        if (frame.bytes == null) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(frame.bytes);
            System.out.write(bytes);
            System.out.flush();
        } catch (Exception e) {
            System.err.println("[hensu] Failed to decode output frame: " + e.getMessage());
        }
    }
}
