package io.hensu.server.api;

import io.hensu.core.util.LogSanitizer;
import io.hensu.server.mcp.McpSessionManager;
import io.hensu.server.security.RequestTenantResolver;
import io.hensu.server.validation.ValidId;
import io.hensu.server.validation.ValidMessage;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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
/// 1. Client authenticates and connects to `GET /mcp/connect`
/// 2. Client receives JSON-RPC requests via SSE (e.g., `tools/call`)
/// 3. Client executes the tool locally
/// 4. Client POSTs result to `POST /mcp/message`
/// 5. Hensu correlates response by JSON-RPC `id` and the caller's tenant
///
/// ### Tenant Binding
/// Every endpoint except the aggregate `/mcp/status` derives the client
/// identity from the authenticated tenant – the JWT `tenant_id` claim read by
/// {@link RequestTenantResolver} – and never from the request. The tenant is
/// resolved eagerly on the resource-method thread and only the resolved string
/// reaches {@link McpSessionManager}, so no stream callback or termination hook
/// depends on the request context still being active.
///
/// ### Example Client (JavaScript)
/// ```javascript
/// // Connect to SSE stream (with JWT auth)
/// const events = new EventSource('/mcp/connect');
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
/// @see io.hensu.mcp.JsonRpc for message formatting
@Path("/mcp")
public class McpGatewayResource {

    private static final Logger LOG = Logger.getLogger(McpGatewayResource.class);

    private final McpSessionManager sessionManager;
    private final RequestTenantResolver tenantResolver;

    /// Creates the gateway resource.
    ///
    /// @param sessionManager registry of inbound tenant sessions, not null
    /// @param tenantResolver resolves the authenticated tenant of the request, not null
    @Inject
    public McpGatewayResource(
            McpSessionManager sessionManager, RequestTenantResolver tenantResolver) {
        this.sessionManager = sessionManager;
        this.tenantResolver = tenantResolver;
    }

    /// SSE endpoint for client connections (downstream pipe).
    ///
    /// Clients connect here using EventSource to receive JSON-RPC requests.
    /// The connection stays open until the client disconnects. The session is
    /// registered under the authenticated tenant; a second connection for the
    /// same tenant supersedes the first.
    ///
    /// The `clientId` parameter is optional. When present it is an assertion of
    /// intent, checked against the tenant claim: a value that disagrees is
    /// refused rather than honoured, so no caller can register as another
    /// tenant's client.
    ///
    /// ### Request
    /// ```
    /// GET /mcp/connect?clientId=tenant-123
    /// Authorization: Bearer <jwt>
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
    /// @param clientId the tenant the caller asserts it connects as, may be null
    /// @return SSE stream of JSON-RPC messages, never null
    /// @throws ForbiddenException if `clientId` is present and differs from the
    ///     authenticated tenant, or if no tenant can be resolved
    @GET
    @Path("/connect")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<String> connect(@QueryParam("clientId") @ValidId String clientId) {
        String tenantId = tenantResolver.tenantId();
        if (clientId != null && !clientId.equals(tenantId)) {
            LOG.warnv(
                    "MCP connection refused: clientId {0} does not match the authenticated tenant",
                    LogSanitizer.sanitize(clientId));
            throw new ForbiddenException("clientId does not match the authenticated tenant");
        }

        LOG.infov("MCP connection request: tenant={0}", LogSanitizer.sanitize(tenantId));

        return sessionManager
                .createSession(tenantId)
                .onSubscription()
                .invoke(
                        () ->
                                LOG.debugv(
                                        "SSE stream started for: {0}",
                                        LogSanitizer.sanitize(tenantId)))
                .onTermination()
                .invoke(
                        (t, _) -> {
                            if (t != null) {
                                LOG.warnv(
                                        t,
                                        "SSE stream error for: {0}",
                                        LogSanitizer.sanitize(tenantId));
                            } else {
                                LOG.debugv(
                                        "SSE stream ended for: {0}",
                                        LogSanitizer.sanitize(tenantId));
                            }
                        });
    }

    /// HTTP POST endpoint for client responses (upstream pipe).
    ///
    /// Clients POST JSON-RPC responses here after executing tool calls.
    /// The response is correlated to the pending request by JSON-RPC `id`, and
    /// completes it only when the caller's tenant owns that request. An id
    /// owned by another tenant is answered exactly like one that never existed,
    /// so the endpoint does not confirm the existence of other tenants' ids.
    ///
    /// The {@link ValidMessage} constraint enforces:
    /// - Non-blank body
    /// - Size within {@link io.hensu.server.validation.InputValidator#MAX_JSON_MESSAGE_BYTES}
    /// - No dangerous control characters
    ///
    /// ### Request
    /// ```
    /// POST /mcp/message
    /// Authorization: Bearer <jwt>
    /// Content-Type: application/json
    ///
    /// {"jsonrpc":"2.0","id":"uuid-1","result":{"content":"file contents..."}}
    /// ```
    ///
    /// ### Response
    /// - 204 No Content: Response accepted, or a message that is not a response –
    ///   a notification, or a request carrying a `method`, which is ignored
    /// - 400 Bad Request: Empty body, oversized payload, or illegal characters
    /// - 404 Not Found: No pending request with that `id` belongs to the caller –
    ///   unknown, timed out, failed by a disconnect, or owned by another tenant.
    ///   A client answering late sees this and should drop the response, not
    ///   treat it as an error
    ///
    /// @param jsonMessage the JSON-RPC response, not null
    /// @return empty response on success, never null
    /// @throws NotFoundException if no pending request with the message's id
    ///     belongs to the caller's tenant
    /// @throws ForbiddenException if no tenant can be resolved
    @POST
    @Path("/message")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> receiveMessage(@ValidMessage String jsonMessage) {
        String tenantId = tenantResolver.tenantId();

        LOG.debugv(
                "Received MCP message: {0}",
                LogSanitizer.sanitize(
                        jsonMessage.substring(0, Math.min(200, jsonMessage.length()))));

        return switch (sessionManager.handleResponse(tenantId, jsonMessage)) {
            case COMPLETED, NOT_A_RESPONSE -> Uni.createFrom().item(Response.noContent().build());
            case UNKNOWN -> throw new NotFoundException("No pending MCP request with that id");
        };
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
                        new GatewayStatusResponse(
                                sessionManager.connectedClientCount(),
                                sessionManager.pendingRequestCount()))
                .build();
    }

    /// Checks if the caller's own client is connected.
    ///
    /// Answers only for the authenticated tenant. Asking about any other client
    /// is answered 404, so the endpoint reveals neither the existence nor the
    /// connection duration of another tenant.
    ///
    /// @param clientId the client to check, must be the caller's tenant, not null
    /// @return connection status, never null
    /// @throws NotFoundException if `clientId` is not the caller's tenant
    /// @throws ForbiddenException if no tenant can be resolved
    @GET
    @Path("/clients/{clientId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clientStatus(@PathParam("clientId") @ValidId String clientId) {
        String tenantId = tenantResolver.tenantId();
        if (!tenantId.equals(clientId)) {
            throw new NotFoundException("Unknown MCP client");
        }
        McpSessionManager.ClientInfo info = sessionManager.getClientInfo(clientId);

        ClientStatusResponse body =
                (info != null && sessionManager.isConnected(clientId))
                        ? ClientStatusResponse.connected(clientId, info.connectedDurationMs())
                        : ClientStatusResponse.disconnected(clientId);

        return Response.ok(body).build();
    }
}
