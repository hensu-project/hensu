package io.hensu.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.execution.action.HermeticEnvironment;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/// MCP client speaking the stdio transport to a server process the engine owns.
///
/// The server is launched, spoken to over its standard input and output as
/// line-delimited JSON-RPC, and killed when the connection closes. That
/// lifetime is the reason several decisions here differ from the HTTP client:
///
/// - **The launch is contained, not the call.** A server process outlives every
///   individual request, so containment is decided once, at launch, by the
///   `argvWrapper` the caller supplies. Passing the wrapper in rather than
///   depending on a sandbox implementation keeps this module free of any
///   runtime's launcher.
/// - **The environment is hermetic.** The child starts from an empty
///   environment and receives {@link HermeticEnvironment#PASSTHROUGH} plus what
///   the server declared. A filesystem server has no business reading the
///   operator's cloud credentials.
/// - **Every request has its own deadline, on both legs.** A server that stops
///   answering must fail the call rather than pin a workflow thread, so each
///   in-flight request carries a deadline and its correlation entry is dropped
///   when that expires. The same deadline covers the send: a server that stops
///   reading its standard input fills the pipe, and a caller waiting on a full
///   pipe would otherwise wait forever.
/// - **The handshake is read, not merely completed.** A server announcing a
///   revision Hensu does not implement fails the connection at launch naming both
///   versions, rather than producing a confusing method error on the first call.
/// - **Coming up is not answering.** The handshake is charged to
///   {@link McpServerSpec#startupTimeoutMs()}, which runs from the fork and so
///   has to cover whatever the server's runtime costs to boot. Every call after
///   it is charged to {@link McpServerSpec#requestTimeoutMs()}, against a
///   process already running. Sharing one budget would make a deployment that
///   wants calls to fail fast unable to start a server at all.
/// - **Standard error is drained.** A server that logs to stderr would block on
///   a full pipe and look like a hang, so a reader thread consumes it and logs
///   it at the connection's own name.
///
/// ### Contracts
/// - **Precondition**: {@link #open} completes the `initialize` handshake before
///   returning, so a connection handed to a caller is a usable one
/// - **Postcondition**: {@link #close} leaves no process and no waiting caller
/// - **Invariant**: standard output carries protocol traffic only
///
/// @implNote **Mutable.** One instance owns one process for the length of a run.
/// Each of the three pipes has its own Virtual Thread – two draining the server's
/// output, one owning its input – so callers on many Virtual Threads may share a
/// connection: the writer serializes the line framing, and no caller blocks on
/// the pipe itself.
/// @see McpServerSpec for the declaration this launches
/// @see McpResultRenderer for turning a call result into text an agent reads
public final class StdioMcpConnection implements McpConnection {

    private static final Logger logger = Logger.getLogger(StdioMcpConnection.class.getName());

    private final McpServerSpec.Stdio spec;
    private final JsonRpc jsonRpc;
    private final Process process;
    private final BufferedWriter requests;
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();
    private final ExecutorService stdin;
    private volatile boolean closed;
    private volatile boolean transportClosed;
    private volatile String serverInfo;

    private StdioMcpConnection(McpServerSpec.Stdio spec, Process process) {
        this.spec = spec;
        this.process = process;
        this.jsonRpc = new JsonRpc(new ObjectMapper());
        this.requests =
                new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.serverInfo = spec.name();
        this.stdin =
                Executors.newSingleThreadExecutor(
                        Thread.ofVirtual().name("mcp-stdin-" + spec.name()).factory());
    }

    /// Launches a server and completes the MCP handshake.
    ///
    /// @param spec the server declaration, not null
    /// @param workingDirectory the directory the server process starts in, not null
    /// @param argvWrapper containment applied to the launch argv – identity when
    ///     the caller has decided to run the server uncontained, not null
    /// @param hostEnvironment the environment to draw the hermetic base from,
    ///     `System.getenv()` in production, not null
    /// @return a connected client whose server answered `initialize`, never null
    /// @throws McpException if the process cannot start or the handshake fails
    /// @throws NullPointerException if any argument is null
    public static StdioMcpConnection open(
            McpServerSpec.Stdio spec,
            Path workingDirectory,
            UnaryOperator<List<String>> argvWrapper,
            Map<String, String> hostEnvironment) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Objects.requireNonNull(argvWrapper, "argvWrapper must not be null");
        Objects.requireNonNull(hostEnvironment, "hostEnvironment must not be null");

        List<String> argv = argvWrapper.apply(spec.command());
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(workingDirectory.toFile());
        builder.environment().clear();
        Map<String, String> environment = HermeticEnvironment.base(hostEnvironment);
        environment.putAll(spec.env());
        builder.environment().putAll(environment);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new McpException(
                    "Could not launch MCP server '" + spec.name() + "': " + e.getMessage(), e);
        }

        StdioMcpConnection connection = new StdioMcpConnection(spec, process);
        connection.startReaders();
        try {
            connection.handshake();
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /// Lists the tools the server currently exposes.
    ///
    /// @return the server's catalog, never null (may be empty)
    /// @throws McpException if the server does not answer within its request timeout
    @Override
    public List<McpToolDescriptor> listTools() {
        Map<String, Object> result = request("tools/list", Map.of(), spec.requestTimeoutMs());
        if (!(result.get("tools") instanceof List<?> published)) {
            return List.of();
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (Object entry : published) {
            if (entry instanceof Map<?, ?> tool && tool.get("name") instanceof String name) {
                descriptors.add(
                        new McpToolDescriptor(
                                name,
                                tool.get("description") instanceof String description
                                        ? description
                                        : "",
                                tool.get("inputSchema") instanceof Map<?, ?> schema
                                        ? castSchema(schema)
                                        : Map.of()));
            }
        }
        return List.copyOf(descriptors);
    }

    /// Invokes a tool on the server.
    ///
    /// @param toolName the tool the agent chose, not null
    /// @param arguments the agent's arguments, not null (may be empty)
    /// @return the raw MCP result, for {@link McpResultRenderer} to render, never null
    /// @throws McpException if the server does not answer within its request timeout
    @Override
    public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);
        return request("tools/call", params, spec.requestTimeoutMs());
    }

    /// Returns a stable identifier for this connection.
    ///
    /// @return the server name prefixed with its transport, never null
    @Override
    public String getEndpoint() {
        return "stdio:" + spec.name();
    }

    /// Returns whether the server can still be spoken to.
    ///
    /// End of file on the server's standard output settles this before the
    /// operating system has reaped the process, so a caller woken by a failed
    /// request reads a stable answer rather than racing the exit.
    ///
    /// @return true while the transport is open and the process is alive
    @Override
    public boolean isConnected() {
        return !closed && !transportClosed && process.isAlive();
    }

    /// Kills the server process and fails every waiting caller.
    ///
    /// The process is destroyed before standard input is closed, and not the
    /// other way round: closing first would flush the buffer on the caller's
    /// thread, and `BufferedWriter` guards its buffer with a monitor a write
    /// stuck in a full pipe still holds. Destroying the process breaks that pipe,
    /// which is what lets the stuck write fail and release it.
    ///
    /// @apiNote **Side effects**: destroys the process tree, so a server that
    ///     spawned children leaves none behind.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        failPending("MCP server '" + spec.name() + "' was closed");
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        stdin.shutdownNow();
        try {
            requests.close();
        } catch (IOException e) {
            logger.log(Level.FINE, "Closing stdin of MCP server " + spec.name(), e);
        }
    }

    /// Returns the declaration this connection was launched from.
    ///
    /// @return the server declaration, never null
    public McpServerSpec.Stdio spec() {
        return spec;
    }

    // ---------------------------------------------------------------- protocol

    /// Completes `initialize` under the launch budget rather than the call one.
    ///
    /// The clock starts at the fork, not when the server is ready, so this wait
    /// pays for the server's whole runtime coming up – a package resolve, an
    /// interpreter start, a class load. Charging that to the per-request budget
    /// is what made a deployment asking for fast failures unable to start.
    private void handshake() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", McpProtocol.LEGACY_REVISION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", McpProtocol.CLIENT_INFO);
        Map<String, Object> result = request("initialize", params, spec.startupTimeoutMs());
        McpProtocol.Handshake announced = McpProtocol.read(result);
        if (announced.revision() != null && !McpProtocol.isSupported(announced.revision())) {
            throw new McpException(
                    McpProtocol.unsupportedRevision(spec.name(), announced.revision()));
        }
        if (announced.serverName() != null) {
            serverInfo = announced.serverName();
        }
        write(
                jsonRpc.createNotification("notifications/initialized", Map.of()),
                spec.startupTimeoutMs());
    }

    /// Returns who is on the other end, for an operator about to approve a call.
    ///
    /// @return the name the server gave itself, or the declared server name when
    ///     it gave none, never null
    @Override
    public String serverInfo() {
        return serverInfo;
    }

    private Map<String, Object> request(String method, Map<String, Object> params, long timeoutMs) {
        if (closed || !process.isAlive()) {
            throw new McpException("MCP server '" + spec.name() + "' is not running");
        }
        String id = String.valueOf(nextId.incrementAndGet());
        CompletableFuture<String> answer = new CompletableFuture<>();
        pending.put(id, answer);
        try {
            write(jsonRpc.createRequest(id, method, params), timeoutMs);
            String response = answer.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).join();
            return jsonRpc.parseResult(response);
        } catch (CompletionException e) {
            throw e.getCause() instanceof TimeoutException
                    ? new McpException(
                            "MCP request '"
                                    + method
                                    + "' to server '"
                                    + spec.name()
                                    + "' timed out after "
                                    + timeoutMs
                                    + "ms")
                    : asMcpException(method, e.getCause());
        } finally {
            pending.remove(id);
        }
    }

    /// Hands one line to the server under the same deadline as its reply.
    ///
    /// The write is as blocking as the read: a server that stops reading its
    /// standard input fills the pipe, and a caller writing into a full pipe waits
    /// with no deadline at all – the request timeout only ever guarded the reply.
    /// The line is therefore written on the connection's own Virtual Thread, which
    /// both serializes the framing and lets the caller give up. A server that
    /// cannot take a request is treated as dead rather than slow, because the
    /// bytes already in the pipe make the next request unframeable.
    private void write(String json, long timeoutMs) {
        Future<?> written;
        try {
            written = stdin.submit(() -> writeLine(json));
        } catch (RejectedExecutionException e) {
            throw new McpException("MCP server '" + spec.name() + "' is not running", e);
        }
        try {
            written.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            written.cancel(true);
            close();
            throw new McpException(
                    "MCP server '"
                            + spec.name()
                            + "' stopped reading its standard input, so the request could not be"
                            + " sent within "
                            + timeoutMs
                            + "ms");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof McpException mcp
                    ? mcp
                    : new McpException(
                            "Could not write to MCP server '" + spec.name() + "'", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(
                    "Interrupted while writing to MCP server '" + spec.name() + "'", e);
        }
    }

    private void writeLine(String json) {
        try {
            requests.write(json);
            requests.write('\n');
            requests.flush();
        } catch (IOException e) {
            throw new McpException(
                    "Could not write to MCP server '" + spec.name() + "': " + e.getMessage(), e);
        }
    }

    private McpException asMcpException(String method, Throwable cause) {
        if (cause instanceof McpException mcp) {
            return mcp;
        }
        return new McpException(
                "MCP request '"
                        + method
                        + "' to server '"
                        + spec.name()
                        + "' failed: "
                        + (cause != null ? cause.getMessage() : "unknown cause"),
                cause);
    }

    // ----------------------------------------------------------------- readers

    private void startReaders() {
        Thread.ofVirtual().name("mcp-stdout-" + spec.name()).start(this::readResponses);
        Thread.ofVirtual().name("mcp-stderr-" + spec.name()).start(this::drainDiagnostics);
    }

    private void readResponses() {
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                dispatch(line);
            }
        } catch (IOException e) {
            if (!closed) {
                logger.log(
                        Level.WARNING,
                        "Lost the connection to MCP server " + spec.name() + ": " + e.getMessage(),
                        e);
            }
        } finally {
            transportClosed = true;
            failPending("MCP server '" + spec.name() + "' is not running");
        }
    }

    private void dispatch(String line) {
        if (line.isBlank() || !jsonRpc.isResponse(line)) {
            // Server-initiated requests and notifications are not part of the
            // client surface this engine needs, so they are dropped rather than
            // answered with an error the server did not ask for.
            return;
        }
        String id = jsonRpc.extractId(line);
        CompletableFuture<String> waiting = id != null ? pending.remove(id) : null;
        if (waiting != null) {
            waiting.complete(line);
        }
    }

    private void drainDiagnostics() {
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String diagnostic = line;
                logger.fine(() -> "[" + spec.name() + "] " + diagnostic);
            }
        } catch (IOException e) {
            logger.log(Level.FINE, "Standard error of MCP server " + spec.name() + " closed", e);
        }
    }

    private void failPending(String reason) {
        pending.values()
                .forEach(waiting -> waiting.completeExceptionally(new McpException(reason)));
        pending.clear();
    }

    private static Map<String, Object> castSchema(Map<?, ?> schema) {
        return (Map<String, Object>) schema;
    }
}
