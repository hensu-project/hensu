package io.hensu.server.mcp;

import io.hensu.server.validation.LogSanitizer;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
/// │  Hensu Engine   │                    │  Tenant Client  │
/// │                 │                    │  (MCP Server)   │
/// │  sendRequest()  │———— SSE ——————————>│  EventSource    │
/// │                 │  (tools/call)      │                 │
/// │                 │                    │                 │
/// │  handleResponse │<——— POST ——————————│  POST /message  │
/// │  (Future.done)  │  (result/error)    │                 │
/// +—————————————————+                    +—————————————————+
/// ```
///
/// ### Thread Safety
/// Thread-safe via ConcurrentHashMap. Designed for high concurrency with
/// virtual threads (Java 25) where blocking on futures is efficient.
///
/// @see io.hensu.server.api.McpGatewayResource for HTTP endpoints
/// @see JsonRpc for message formatting
@ApplicationScoped
public class McpSessionManager {

    private static final Logger LOG = Logger.getLogger(McpSessionManager.class);

    /// Default timeout for tool calls.
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /// Maps clientId -> active SSE emitter.
    private final Map<String, MultiEmitter<? super String>> emitters = new ConcurrentHashMap<>();

    /// Maps JSON-RPC request ID -> pending response future.
    private final Map<String, CompletableFuture<String>> pendingRequests =
            new ConcurrentHashMap<>();

    /// Maps clientId -> client metadata for observability.
    private final Map<String, ClientInfo> clients = new ConcurrentHashMap<>();

    private final JsonRpc jsonRpc;

    @Inject
    public McpSessionManager(JsonRpc jsonRpc) {
        this.jsonRpc = jsonRpc;
    }

    /// Creates a new SSE session for a client.
    ///
    /// Returns a Multi that keeps the connection open. The client receives
    /// JSON-RPC requests via this stream until they disconnect.
    ///
    /// @param clientId unique client identifier (typically tenant ID)
    /// @return SSE event stream
    public Multi<String> createSession(String clientId) {
        return Multi.createFrom()
                .emitter(
                        emitter -> {
                            LOG.infov("MCP client connected: {0}", LogSanitizer.sanitize(clientId));

                            // Register the emitter
                            emitters.put(clientId, emitter);
                            clients.put(
                                    clientId, new ClientInfo(clientId, System.currentTimeMillis()));

                            // Send initial ping/handshake
                            String ping =
                                    jsonRpc.createNotification("ping", Map.of("server", "hensu"));
                            emitter.emit(ping);

                            // Cleanup on disconnect
                            emitter.onTermination(
                                    () -> {
                                        LOG.infov(
                                                "MCP client disconnected: {0}",
                                                LogSanitizer.sanitize(clientId));
                                        emitters.remove(clientId);
                                        clients.remove(clientId);

                                        // Cancel any pending requests for this client
                                        cancelPendingRequests(clientId);
                                    });
                        });
    }

    /// Sends a JSON-RPC request to a client and waits for response.
    ///
    /// This method pushes the request via SSE and returns a Uni that resolves
    /// when the client responds via HTTP POST.
    ///
    /// @param clientId the target client
    /// @param method the JSON-RPC method (e.g., "tools/call")
    /// @param params the method parameters
    /// @return Uni containing the JSON-RPC response
    public Uni<String> sendRequest(String clientId, String method, Object params) {
        return sendRequest(clientId, method, params, DEFAULT_TIMEOUT);
    }

    /// Sends a JSON-RPC request with custom timeout.
    ///
    /// @param clientId the target client
    /// @param method the JSON-RPC method
    /// @param params the method parameters
    /// @param timeout maximum wait time for response
    /// @return Uni containing the JSON-RPC response
    public Uni<String> sendRequest(
            String clientId, String method, Object params, Duration timeout) {
        MultiEmitter<? super String> emitter = emitters.get(clientId);
        if (emitter == null || emitter.isCancelled()) {
            return Uni.createFrom().failure(new McpException("Client not connected: " + clientId));
        }

        String requestId = UUID.randomUUID().toString();
        String jsonRequest = jsonRpc.createRequest(requestId, method, params);

        // Prepare future for response correlation
        CompletableFuture<String> responseFuture = new CompletableFuture<>();
        pendingRequests.put(requestId, responseFuture);

        // Push request down SSE pipe
        try {
            LOG.debugv(
                    "Sending MCP request to {0}: method={1}, id={2}",
                    LogSanitizer.sanitize(clientId), method, requestId);
            emitter.emit(jsonRequest);
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            return Uni.createFrom()
                    .failure(new McpException("Failed to send request: " + e.getMessage(), e));
        }

        // Convert Future to Uni with timeout
        return Uni.createFrom()
                .completionStage(responseFuture)
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
    /// @param clientId the target client
    /// @param method the notification method
    /// @param params the parameters
    public void sendNotification(String clientId, String method, Object params) {
        MultiEmitter<? super String> emitter = emitters.get(clientId);
        if (emitter == null || emitter.isCancelled()) {
            LOG.warnv(
                    "Cannot send notification, client not connected: {0}",
                    LogSanitizer.sanitize(clientId));
            return;
        }

        String notification = jsonRpc.createNotification(method, params);
        try {
            emitter.emit(notification);
            LOG.debugv("Sent notification to {0}: {1}", clientId, method);
        } catch (Exception e) {
            LOG.warnv(e, "Failed to send notification to {0}", clientId);
        }
    }

    /// Handles an incoming JSON-RPC response from HTTP POST.
    ///
    /// Finds the pending future by request ID and completes it.
    ///
    /// @param jsonResponse the JSON-RPC response
    public void handleResponse(String jsonResponse) {
        String id = jsonRpc.extractId(jsonResponse);

        if (id == null) {
            LOG.warnv(
                    "Received response without ID: {0}",
                    LogSanitizer.sanitize(
                            jsonResponse.substring(0, Math.min(100, jsonResponse.length()))));
            return;
        }

        CompletableFuture<String> future = pendingRequests.remove(id);
        if (future != null) {
            LOG.debugv("Completing request {0}", id);
            future.complete(jsonResponse);
        } else {
            LOG.warnv("Received response for unknown or timed-out ID: {0}", id);
        }
    }

    /// Checks if a client is connected.
    ///
    /// @param clientId the client to check
    /// @return true if connected
    public boolean isConnected(String clientId) {
        MultiEmitter<? super String> emitter = emitters.get(clientId);
        return emitter != null && !emitter.isCancelled();
    }

    /// Returns the number of connected clients.
    ///
    /// @return connected client count
    public int connectedClientCount() {
        return emitters.size();
    }

    /// Returns the number of pending requests.
    ///
    /// @return pending request count
    public int pendingRequestCount() {
        return pendingRequests.size();
    }

    /// Gets info about a connected client.
    ///
    /// @param clientId the client ID
    /// @return client info, or null if not connected
    public ClientInfo getClientInfo(String clientId) {
        return clients.get(clientId);
    }

    /// Disconnects a client forcefully.
    ///
    /// @param clientId the client to disconnect
    public void disconnect(String clientId) {
        MultiEmitter<? super String> emitter = emitters.remove(clientId);
        if (emitter != null) {
            emitter.complete();
        }
        clients.remove(clientId);
        cancelPendingRequests(clientId);
    }

    /// Cancels all pending requests for a client.
    private void cancelPendingRequests(String clientId) {
        // Note: We don't track requests by client, so we can't cancel selectively.
        // In production, you might want a clientId -> Set<requestId> mapping.
        LOG.debugv(
                "Client {0} disconnected, pending requests may time out",
                LogSanitizer.sanitize(clientId));
    }

    /// Client connection metadata.
    public record ClientInfo(String clientId, long connectedAt) {

        /// Returns how long the client has been connected in milliseconds.
        public long connectedDurationMs() {
            return System.currentTimeMillis() - connectedAt;
        }
    }
}
