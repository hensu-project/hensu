package io.hensu.server.mcp;

import io.hensu.core.util.LogSanitizer;
import io.hensu.mcp.JsonRpc;
import io.hensu.mcp.McpException;
import io.hensu.server.persistence.ExecutionLeaseManager;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/// MCP session manager for the split-pipe SSE transport.
///
/// Manages the bidirectional MCP communication:
/// - **Downstream (SSE)**: Pushes JSON-RPC requests to connected clients via MultiEmitter
/// - **Upstream (POST)**: Receives JSON-RPC responses and completes pending futures
///
/// ### Architecture
/// ```
/// +—————————————————+                    +—————————————————+
/// │  Hensu Server   │                    │  Tenant Client  │
/// │                 │                    │  (bridge)       │
/// │  sendRequest()  │———— SSE ——————————>│  EventSource    │
/// │                 │  (tools/call)      │                 │
/// │                 │                    │                 │
/// │  handleResponse │<——— POST ——————————│  POST /message  │
/// │  (Future.done)  │  (result/error)    │                 │
/// +—————————————————+                    +—————————————————+
/// ```
///
/// ### Session Fencing
/// A client holds at most one session. A second connection for the same client
/// supersedes the first: the earlier stream is completed deliberately and the
/// requests pushed down it are failed, since the successor never saw them. Each
/// session's termination hook removes the registry entry only if that entry is
/// still the session the hook belongs to, so a displaced stream that closes late
/// can never evict the session that replaced it.
///
/// ### Request Ownership
/// Every pending request records the session it was pushed down. When that
/// session ends – client disconnect, supersede, or {@link #disconnect(String)} –
/// its requests fail at once with a message naming the disconnect, instead of
/// waiting out their timeout. A response completes a request only when it comes
/// from the tenant that owns it.
///
/// ### Session Ownership Across Nodes
/// The current session of each client is published to `runtime.mcp_sessions`
/// through {@link ExecutionLeaseManager}, so that claiming can keep an
/// execution on the node holding its tenant's stream. Those writes are
/// blocking JDBC, while registration and termination run on the Vert.x event
/// loop, so they are handed to one serial writer backed by a Virtual Thread.
/// A single queue applies them in the order the event loop issued them, which
/// is what keeps "disconnect, then reconnect" from landing as publish-then-
/// delete and erasing the live session's row. The heartbeat's refresh goes
/// through the same queue (see {@link #refreshSessions()}), so it cannot
/// re-insert a row a clear has just deleted. A failed write is logged and not
/// retried: a missing row is re-inserted by the next heartbeat refresh, and a
/// row that should have gone ages out at the stale threshold.
///
/// @implNote **Mutable. Thread-safe.** All registry state lives in
///     `ConcurrentHashMap`s; session termination is idempotent. Registration and
///     termination run on the Vert.x event loop and never block; callers of
///     {@link #sendRequest} block on the returned `Uni` from Virtual Threads.
/// @see io.hensu.server.api.McpGatewayResource for HTTP endpoints
/// @see McpSessionChanged for the event fired as sessions begin and end
/// @see JsonRpc for message formatting
@ApplicationScoped
public class McpSessionManager {

    private static final Logger LOG = Logger.getLogger(McpSessionManager.class);

    /// Default timeout for tool calls.
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /// Maps clientId -> the client's current session.
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /// Maps JSON-RPC request ID -> the in-flight request awaiting its response.
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    private final JsonRpc jsonRpc;
    private final Consumer<McpSessionChanged> sessionChanged;
    private final ExecutionLeaseManager leaseManager;
    private final Executor sessionWrites;

    /// Creates the session manager wired to CDI.
    ///
    /// @param jsonRpc helper used to build and parse JSON-RPC messages, not null
    /// @param sessionChanged CDI event fired as sessions begin and end, not null
    /// @param leaseManager publishes session ownership for claiming, not null
    @Inject
    public McpSessionManager(
            JsonRpc jsonRpc,
            Event<McpSessionChanged> sessionChanged,
            ExecutionLeaseManager leaseManager) {
        this(
                jsonRpc,
                sessionChanged::fire,
                leaseManager,
                Executors.newSingleThreadExecutor(
                        Thread.ofVirtual().name("mcp-session-writes").factory()));
    }

    /// Creates a session manager with an explicit listener and session writer.
    ///
    /// @param jsonRpc helper used to build and parse JSON-RPC messages, not null
    /// @param sessionChanged listener notified as sessions begin and end, not null
    /// @param leaseManager publishes session ownership for claiming, not null
    /// @param sessionWrites runs session-row writes in submission order, not null
    McpSessionManager(
            JsonRpc jsonRpc,
            Consumer<McpSessionChanged> sessionChanged,
            ExecutionLeaseManager leaseManager,
            Executor sessionWrites) {
        this.jsonRpc = Objects.requireNonNull(jsonRpc, "jsonRpc must not be null");
        this.sessionChanged =
                Objects.requireNonNull(sessionChanged, "sessionChanged must not be null");
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager must not be null");
        this.sessionWrites =
                Objects.requireNonNull(sessionWrites, "sessionWrites must not be null");
    }

    /// Drains queued session-row writes and stops the writer.
    @PreDestroy
    void shutdown() {
        if (sessionWrites instanceof ExecutorService executor) {
            executor.close();
        }
    }

    /// Creates a new SSE session for a client.
    ///
    /// Returns a Multi that keeps the connection open. The client receives
    /// JSON-RPC requests via this stream until they disconnect. The session
    /// registers when the stream is subscribed, superseding any session the
    /// client already holds.
    ///
    /// @apiNote **Side effects** on subscription:
    /// - Completes the client's previous stream, if any, and fails the requests
    ///   pending on it
    /// - Fires {@link McpSessionChanged} on registration, and again when this
    ///   session terminates while still current
    /// - Queues a write publishing this node as the session's holder, and a
    ///   write clearing it when this session terminates while still current
    ///
    /// @param clientId unique client identifier, the authenticated tenant id, not null
    /// @return SSE event stream, never null
    public Multi<String> createSession(String clientId) {
        Objects.requireNonNull(clientId, "clientId must not be null");
        return Multi.createFrom().emitter(emitter -> register(clientId, emitter));
    }

    /// Sends a JSON-RPC request to a client and waits for response.
    ///
    /// This method pushes the request via SSE and returns a Uni that resolves
    /// when the client responds via HTTP POST.
    ///
    /// @param clientId the target client, not null
    /// @param method the JSON-RPC method (e.g., "tools/call"), not null
    /// @param params the method parameters, not null
    /// @return Uni containing the JSON-RPC response, never null; fails with
    ///     {@link McpException} if the client is not connected, disconnects before
    ///     answering, or does not answer within the default timeout
    public Uni<String> sendRequest(String clientId, String method, Object params) {
        return sendRequest(clientId, method, params, DEFAULT_TIMEOUT);
    }

    /// Sends a JSON-RPC request with custom timeout.
    ///
    /// @param clientId the target client, not null
    /// @param method the JSON-RPC method, not null
    /// @param params the method parameters, not null
    /// @param timeout maximum wait time for response, not null
    /// @return Uni containing the JSON-RPC response, never null; fails with
    ///     {@link McpException} if the client is not connected, disconnects before
    ///     answering, or does not answer within `timeout`
    public Uni<String> sendRequest(
            String clientId, String method, Object params, Duration timeout) {
        Session session = sessions.get(clientId);
        if (session == null || session.emitter.isCancelled()) {
            return Uni.createFrom().failure(() -> notConnected(clientId));
        }

        String requestId = UUID.randomUUID().toString();
        String jsonRequest = jsonRpc.createRequest(requestId, method, params);

        // Prepare future for response correlation
        PendingRequest pending = new PendingRequest(session, method, new CompletableFuture<>());
        pendingRequests.put(requestId, pending);

        // The session may have terminated between the lookup and the put, after its
        // termination already failed the requests it owned. Fail this one here, or it
        // would wait out the full timeout for a stream that no longer exists.
        if (session.closed.get()) {
            pendingRequests.remove(requestId);
            return Uni.createFrom().failure(disconnected(pending));
        }

        // Push request down SSE pipe
        try {
            LOG.debugv(
                    "Sending MCP request to {0}: method={1}, id={2}",
                    LogSanitizer.sanitize(clientId), method, requestId);
            session.emitter.emit(jsonRequest);
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            return Uni.createFrom()
                    .failure(new McpException("Failed to send request: " + e.getMessage(), e));
        }

        // Convert Future to Uni with timeout
        return Uni.createFrom()
                .completionStage(pending.future)
                .ifNoItem()
                .after(timeout)
                .failWith(
                        () ->
                                new McpException(
                                        "Request timed out after "
                                                + timeout.toSeconds()
                                                + "s: "
                                                + method))
                .onTermination()
                .invoke(() -> pendingRequests.remove(requestId));
    }

    /// Sends a JSON-RPC notification (no response expected).
    ///
    /// Logs and returns if the client is not connected.
    ///
    /// @param clientId the target client, not null
    /// @param method the notification method, not null
    /// @param params the parameters, not null
    public void sendNotification(String clientId, String method, Object params) {
        Session session = sessions.get(clientId);
        if (session == null || session.emitter.isCancelled()) {
            LOG.warnv(
                    "Cannot send notification, client not connected: {0}",
                    LogSanitizer.sanitize(clientId));
            return;
        }

        String notification = jsonRpc.createNotification(method, params);
        try {
            session.emitter.emit(notification);
            LOG.debugv("Sent notification to {0}: {1}", clientId, method);
        } catch (Exception e) {
            LOG.warnv(e, "Failed to send notification to {0}", clientId);
        }
    }

    /// Handles an incoming JSON-RPC response from HTTP POST.
    ///
    /// Completes the pending request with the response's id, but only when the
    /// responding tenant owns that request. A request owned by another tenant is
    /// left pending and reported exactly like an id that never existed, so the
    /// outcome does not confirm the existence of ids the caller has no business
    /// knowing.
    ///
    /// @param tenantId the authenticated tenant that sent the response, not null
    /// @param jsonResponse the JSON-RPC response, not null
    /// @return how the response was correlated, never null
    public ResponseOutcome handleResponse(String tenantId, String jsonResponse) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String id = jsonRpc.isRequest(jsonResponse) ? null : jsonRpc.extractId(jsonResponse);

        if (id == null) {
            LOG.debugv(
                    "Received a message that is not a response: {0}",
                    LogSanitizer.sanitize(
                            jsonResponse.substring(0, Math.min(100, jsonResponse.length()))));
            return ResponseOutcome.NOT_A_RESPONSE;
        }

        PendingRequest pending = pendingRequests.get(id);
        if (pending == null
                || !pending.owner.clientId.equals(tenantId)
                || !pendingRequests.remove(id, pending)) {
            LOG.warnv(
                    "Received response for unknown, foreign or timed-out ID: {0}",
                    LogSanitizer.sanitize(id));
            return ResponseOutcome.UNKNOWN;
        }

        LOG.debugv("Completing request {0}", id);
        pending.future.complete(jsonResponse);
        return ResponseOutcome.COMPLETED;
    }

    /// Checks if a client is connected.
    ///
    /// @param clientId the client to check, not null
    /// @return true if the client holds a live session on this node
    public boolean isConnected(String clientId) {
        Session session = sessions.get(clientId);
        return session != null && !session.emitter.isCancelled();
    }

    /// Builds the failure for a client with no live session on this node.
    ///
    /// Names the cross-node case when it is the cause: if the tenant's fresh
    /// session is held by another node, the message says so and names both
    /// nodes, because "not connected" would send an operator looking for a relay
    /// that is in fact connected. `"Client not connected"` is reserved for a
    /// tenant with no session on any node.
    ///
    /// @apiNote Issues a blocking JDBC read. Call from a Virtual Thread or worker
    /// thread, never from the event loop.
    ///
    /// @param clientId the client that has no live session here, not null
    /// @return the exception describing the cause, never null
    public McpException notConnected(String clientId) {
        Optional<String> owner;
        try {
            owner = leaseManager.sessionElsewhere(clientId);
        } catch (RuntimeException e) {
            LOG.warnv("Could not look up the MCP session owner: {0}", e.getMessage());
            owner = Optional.empty();
        }
        return owner.map(
                        node ->
                                new McpException(
                                        ExecutionLeaseManager.describeSessionElsewhere(
                                                        clientId, node)
                                                + ", not on this node "
                                                + leaseManager.getServerNodeId()))
                .orElseGet(() -> new McpException("Client not connected: " + clientId));
    }

    /// Refreshes the session rows of the clients connected here, and retires the
    /// streams whose session has moved to another node.
    ///
    /// Runs on the serial session writer, after every publish and clear queued
    /// before it, and reads the registry when it runs rather than when it is
    /// called. A session that terminated before the refresh is therefore not
    /// refreshed, and one that terminates during it queues its clear behind it –
    /// so a refresh can never re-insert the row of a session that has just ended.
    /// Only sessions whose own publish has already run take part: a newer
    /// session's row is not judged until it has been written.
    ///
    /// A session whose row this node had written and another node now holds was
    /// superseded there – the relay reconnected elsewhere and left this stream
    /// half-open. It is terminated like a local supersede, so its pending
    /// requests fail now and it stops counting as connected here. A session whose
    /// publish failed proves nothing by finding another node's row, which may
    /// predate it, so it is left alone until a refresh writes its row.
    ///
    /// @apiNote **Side effects**: queues one write; never blocks the caller.
    public void refreshSessions() {
        writeSession(
                "refresh this node's MCP session rows",
                () -> {
                    List<Session> announced =
                            sessions.values().stream().filter(s -> s.announced).toList();
                    if (announced.isEmpty()) {
                        return;
                    }
                    Set<String> heldElsewhere =
                            leaseManager.refreshSessions(
                                    announced.stream().map(s -> s.clientId).toList());
                    for (Session session : announced) {
                        if (!heldElsewhere.contains(session.clientId)) {
                            session.published = true;
                        } else if (session.published) {
                            LOG.infov(
                                    "MCP client {0} reconnected to another node; retiring its"
                                            + " stream here",
                                    LogSanitizer.sanitize(session.clientId));
                            terminate(session);
                            session.emitter.complete();
                        }
                    }
                });
    }

    /// Returns the number of connected clients.
    ///
    /// @return connected client count
    public int connectedClientCount() {
        return sessions.size();
    }

    /// Returns the number of pending requests.
    ///
    /// @return pending request count
    public int pendingRequestCount() {
        return pendingRequests.size();
    }

    /// Gets info about a connected client.
    ///
    /// @param clientId the client ID, not null
    /// @return client info, or null if not connected
    public ClientInfo getClientInfo(String clientId) {
        Session session = sessions.get(clientId);
        return session != null ? new ClientInfo(session.clientId, session.connectedAt) : null;
    }

    /// Disconnects a client forcefully.
    ///
    /// Completes the client's stream and fails every request pending on it.
    /// Does nothing if the client holds no session.
    ///
    /// @param clientId the client to disconnect, not null
    public void disconnect(String clientId) {
        Session session = sessions.get(clientId);
        if (session != null) {
            terminate(session);
            session.emitter.complete();
        }
    }

    private void register(String clientId, MultiEmitter<? super String> emitter) {
        Session session = new Session(clientId, emitter, System.currentTimeMillis());
        Session previous = sessions.put(clientId, session);
        emitter.onTermination(() -> terminate(session));
        sessionChanged.accept(new McpSessionChanged(clientId));
        // Queued before the displaced session is completed below; that session is no
        // longer current, so its termination queues nothing that could follow this.
        writeSession(
                "publish the MCP session row for " + LogSanitizer.sanitize(clientId),
                () -> {
                    try {
                        leaseManager.publishSession(clientId);
                        session.published = true;
                    } finally {
                        session.announced = true;
                    }
                });

        if (previous != null) {
            LOG.infov(
                    "MCP client reconnected, superseding its previous stream: {0}",
                    LogSanitizer.sanitize(clientId));
            // The previous session is no longer in the registry, so its termination
            // fails only the requests it owns and leaves the successor untouched.
            terminate(previous);
            previous.emitter.complete();
        } else {
            LOG.infov("MCP client connected: {0}", LogSanitizer.sanitize(clientId));
        }

        // Send initial ping/handshake
        emitter.emit(jsonRpc.createNotification("ping", Map.of("server", "hensu")));
    }

    /// Ends a session: fails the requests pushed down it and, if it is still the
    /// client's current session, unregisters it and announces the change.
    /// Idempotent – the stream's termination hook and an explicit supersede or
    /// disconnect may both reach it.
    private void terminate(Session session) {
        if (!session.closed.compareAndSet(false, true)) {
            return;
        }
        boolean wasCurrent = sessions.remove(session.clientId, session);

        pendingRequests
                .entrySet()
                .removeIf(
                        entry -> {
                            PendingRequest pending = entry.getValue();
                            if (pending.owner != session) {
                                return false;
                            }
                            pending.future.completeExceptionally(disconnected(pending));
                            return true;
                        });

        if (wasCurrent) {
            LOG.infov("MCP client disconnected: {0}", LogSanitizer.sanitize(session.clientId));
            sessionChanged.accept(new McpSessionChanged(session.clientId));
            writeSession(
                    "clear the MCP session row for " + LogSanitizer.sanitize(session.clientId),
                    () -> leaseManager.clearSession(session.clientId));
        }
    }

    /// Queues a session-row write on the serial writer. Never blocks the caller.
    ///
    /// @param action what the write does, for the log, already sanitized
    private void writeSession(String action, Runnable write) {
        try {
            sessionWrites.execute(
                    () -> {
                        try {
                            write.run();
                        } catch (RuntimeException e) {
                            LOG.warnv("Failed to {0}: {1}", action, e.getMessage());
                        }
                    });
        } catch (RejectedExecutionException e) {
            LOG.debugv("Skipped: {0}; the session writer is shut down", action);
        }
    }

    private static McpException disconnected(PendingRequest pending) {
        return new McpException(
                "MCP client '"
                        + pending.owner.clientId
                        + "' disconnected before answering "
                        + pending.method);
    }

    /// Outcome of correlating an upstream message with a pending request.
    ///
    /// - {@link #COMPLETED} – the message answered a request the sender owns
    /// - {@link #NOT_A_RESPONSE} – the message carries a `method` or no `id`: a
    ///   notification or request from the client, not an answer to one of ours
    /// - {@link #UNKNOWN} – no pending request with that id belongs to the sender,
    ///   whether it never existed, timed out, or is owned by another tenant
    public enum ResponseOutcome {
        /// The message answered a request the sender owns.
        COMPLETED,
        /// The message is a client notification or request, not a response.
        NOT_A_RESPONSE,
        /// No pending request with that id belongs to the sender.
        UNKNOWN
    }

    /// Client connection metadata.
    ///
    /// @param clientId the connected client, not null
    /// @param connectedAt when the current session registered, epoch milliseconds
    public record ClientInfo(String clientId, long connectedAt) {

        /// Returns how long the client has been connected in milliseconds.
        ///
        /// @return elapsed time since `connectedAt`, in milliseconds
        public long connectedDurationMs() {
            return System.currentTimeMillis() - connectedAt;
        }
    }

    /// One client stream. Compared by identity: two sessions for the same client
    /// are different sessions, which is what fencing relies on.
    private static final class Session {
        private final String clientId;
        private final MultiEmitter<? super String> emitter;
        private final long connectedAt;
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Set once this session's publish write has run, whether or not it
        /// succeeded. Until then the row says nothing about this session.
        private volatile boolean announced;

        /// Set once this node has written the session's row – by its publish, or
        /// by a refresh that re-inserted it. Only then does finding another
        /// node's row mean this session was superseded.
        private volatile boolean published;

        private Session(String clientId, MultiEmitter<? super String> emitter, long connectedAt) {
            this.clientId = clientId;
            this.emitter = emitter;
            this.connectedAt = connectedAt;
        }
    }

    /// A request pushed down a session and awaiting its response.
    private record PendingRequest(Session owner, String method, CompletableFuture<String> future) {}
}
