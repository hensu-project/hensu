package io.hensu.server.api;

import io.hensu.server.mcp.McpSessionManager;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

/// MCP Gateway REST resource implementing the split-pipe SSE transport.
///
/// Provides the HTTP endpoints for MCP over SSE:
/// - **GET /mcp/connect**: SSE stream where clients receive JSON-RPC requests
/// - **POST /mcp/message**: HTTP endpoint where clients send JSON-RPC responses
///
/// ### Split-Pipe Architecture
/// ```
/// Downstream (Server → Client): SSE stream pushes tool call requests
/// Upstream (Client → Server): HTTP POST receives tool results
/// ```
///
/// ### Client Connection Flow
/// 1. Client connects to `GET /mcp/connect?clientId=tenant-123`
/// 2. Client receives JSON-RPC requests via SSE (e.g., `tools/call`)
/// 3. Client executes the tool locally
/// 4. Client POSTs result to `POST /mcp/message`
/// 5. Hensu correlates response by JSON-RPC `id`
///
/// ### Example Client (JavaScript)
/// ```javascript
/// // Connect to SSE stream
/// const events = new EventSource('/mcp/connect?clientId=my-tenant');
/// events.onmessage = async (e) => {
///     const request = JSON.parse(e.data);
///     if (request.method === 'tools/call') {
///         const result = await executeToolLocally(request.params);
///         await fetch('/mcp/message', {
///             method: 'POST',
///             headers: { 'Content-Type': 'application/json' },
///             body: JSON.stringify({ jsonrpc: '2.0', id: request.id, result })
///         });
///     }
/// };
/// ```
///
/// @see McpSessionManager for session and request management
/// @see io.hensu.server.mcp.JsonRpc for message formatting
@Path("/mcp")
public class McpGatewayResource {

    private static final Logger LOG = Logger.getLogger(McpGatewayResource.class);

    private final McpSessionManager sessionManager;

    @Inject
    public McpGatewayResource(McpSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /// SSE endpoint for client connections (downstream pipe).
    ///
    /// Clients connect here using EventSource to receive JSON-RPC requests.
    /// The connection stays open until the client disconnects.
    ///
    /// ### Request
    /// ```
    /// GET /mcp/connect?clientId=tenant-123
    /// Accept: text/event-stream
    /// ```
    ///
    /// ### Response (SSE stream)
    /// ```
    /// data: {"jsonrpc":"2.0","method":"ping","params":{"server":"hensu"}}
    ///
    /// data: {"jsonrpc":"2.0","id":"uuid-1","method":"tools/call","params":{...}}
    /// ```
    ///
    /// @param clientId unique client identifier (typically tenant ID)
    /// @return SSE stream of JSON-RPC messages
    @GET
    @Path("/connect")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<String> connect(@QueryParam("clientId") String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new BadRequestException("clientId query parameter is required");
        }

        LOG.infov("MCP connection request: clientId={0}", clientId);

        return sessionManager
                .createSession(clientId)
                .onSubscription()
                .invoke(() -> LOG.debugv("SSE stream started for: {0}", clientId))
                .onTermination()
                .invoke(
                        (t, c) -> {
                            if (t != null) {
                                LOG.warnv(t, "SSE stream error for: {0}", clientId);
                            } else {
                                LOG.debugv("SSE stream ended for: {0}", clientId);
                            }
                        });
    }

    /// HTTP POST endpoint for client responses (upstream pipe).
    ///
    /// Clients POST JSON-RPC responses here after executing tool calls.
    /// The response is correlated to the pending request by JSON-RPC `id`.
    ///
    /// ### Request
    /// ```
    /// POST /mcp/message
    /// Content-Type: application/json
    ///
    /// {"jsonrpc":"2.0","id":"uuid-1","result":{"content":"file contents..."}}
    /// ```
    ///
    /// ### Response
    /// - 204 No Content: Response accepted
    /// - 400 Bad Request: Invalid JSON-RPC format
    ///
    /// @param jsonMessage the JSON-RPC response
    /// @return empty response on success
    @POST
    @Path("/message")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> receiveMessage(String jsonMessage) {
        if (jsonMessage == null || jsonMessage.isBlank()) {
            return Uni.createFrom()
                    .item(
                            Response.status(Response.Status.BAD_REQUEST)
                                    .entity(Map.of("error", "Empty message body"))
                                    .build());
        }

        LOG.debugv(
                "Received MCP message: {0}",
                jsonMessage.substring(0, Math.min(200, jsonMessage.length())));

        sessionManager.handleResponse(jsonMessage);

        return Uni.createFrom().item(Response.noContent().build());
    }

    /// Health check endpoint for MCP gateway status.
    ///
    /// Returns connection statistics for monitoring.
    ///
    /// ### Request
    /// ```
    /// GET /mcp/status
    /// ```
    ///
    /// ### Response
    /// ```json
    /// {
    ///   "connectedClients": 5,
    ///   "pendingRequests": 12
    /// }
    /// ```
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status() {
        return Response.ok(
                        Map.of(
                                "connectedClients", sessionManager.connectedClientCount(),
                                "pendingRequests", sessionManager.pendingRequestCount()))
                .build();
    }

    /// Checks if a specific client is connected.
    ///
    /// @param clientId the client to check
    /// @return connection status
    @GET
    @Path("/clients/{clientId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clientStatus(@jakarta.ws.rs.PathParam("clientId") String clientId) {
        boolean connected = sessionManager.isConnected(clientId);
        McpSessionManager.ClientInfo info = sessionManager.getClientInfo(clientId);

        if (connected && info != null) {
            return Response.ok(
                            Map.of(
                                    "clientId",
                                    clientId,
                                    "connected",
                                    true,
                                    "connectedDurationMs",
                                    info.connectedDurationMs()))
                    .build();
        } else {
            return Response.ok(Map.of("clientId", clientId, "connected", false)).build();
        }
    }
}
