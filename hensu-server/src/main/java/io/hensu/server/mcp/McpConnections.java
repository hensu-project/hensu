package io.hensu.server.mcp;

import io.hensu.mcp.JsonRpc;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;

/// Opens MCP connections for the server runtime.
///
/// The server speaks exactly one MCP transport: the split pipe, in which the
/// tenant connects inbound to `GET /mcp/connect` and Hensu answers over that
/// already-open stream. An endpoint is therefore never a URL – it is the
/// `sse://clientId` handle of a session the tenant established – and this class
/// refuses anything else rather than reaching outward.
///
/// ### Why There Is No Outbound Client
/// Decision 3 of `docs/unified-architecture.md` makes the server a pure
/// orchestrator whose every side effect travels outbound over the split pipe.
/// "This process opens no outbound connections" is only provable while no
/// outbound client exists, so the seam that once suggested one – a connection
/// factory keyed on scheme – was removed rather than left unimplemented. The
/// CLI is the runtime that dials out, over stdio and Streamable HTTP.
///
/// ### Nothing Is Cached
/// A split-pipe connection owns no socket and no handshake state: it is a thin
/// router over {@link McpSessionManager}, which holds the one resource that
/// matters. Constructing a fresh {@link SseMcpConnection} per call is therefore
/// cheaper than keeping a map alive, and it cannot serve a stale session.
///
/// @implNote **Immutable after construction. Thread-safe.** Holds only the
///     session manager and the JSON-RPC helper, both of which are themselves
///     safe to share across Virtual Threads.
/// @implNote **Server layer only.** Depends on Quarkus ArC and CDI. Do not
///     reference from `hensu-core` or `hensu-dsl`.
/// @see McpSessionManager for the SSE transport this delegates to
/// @see SseMcpConnection for the connection it hands back
@ApplicationScoped
public class McpConnections {

    private static final String SSE_PREFIX = "sse://";

    private final McpSessionManager sessionManager;
    private final JsonRpc jsonRpc;

    /// Creates the connection source over the server's split-pipe infrastructure.
    ///
    /// @param sessionManager registry of inbound tenant sessions, not null
    /// @param jsonRpc helper used to parse responses off the pipe, not null
    @Inject
    public McpConnections(McpSessionManager sessionManager, JsonRpc jsonRpc) {
        this.sessionManager =
                Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.jsonRpc = Objects.requireNonNull(jsonRpc, "jsonRpc must not be null");
    }

    /// Opens a connection to the given endpoint.
    ///
    /// ### Contracts
    /// - **Precondition**: `endpoint` is an `sse://clientId` handle whose client
    ///   has an open inbound stream
    /// - **Postcondition**: returns a live connection bound to that client
    ///
    /// @param endpoint the MCP endpoint handle, may be null
    /// @return a connection for the endpoint, never null
    /// @throws McpException if the endpoint names a transport the server does
    ///     not speak, or if its client is not connected
    public McpConnection get(String endpoint) throws McpException {
        if (endpoint != null && endpoint.startsWith(SSE_PREFIX)) {
            return getSseConnection(endpoint.substring(SSE_PREFIX.length()));
        }
        throw new McpException(
                "Unsupported MCP endpoint '"
                        + endpoint
                        + "': the server speaks the split-pipe transport only, so an endpoint"
                        + " must be an 'sse://clientId' handle. Outbound MCP clients live in"
                        + " the CLI.");
    }

    /// Opens a connection to a client that has an inbound stream open.
    ///
    /// @param clientId the client that connected via SSE, not null
    /// @return SSE-based connection, never null
    /// @throws McpException if the client is not connected
    public McpConnection getSseConnection(String clientId) throws McpException {
        if (!sessionManager.isConnected(clientId)) {
            throw new McpException("Client not connected via SSE: " + clientId);
        }
        return new SseMcpConnection(clientId, sessionManager, jsonRpc);
    }
}
