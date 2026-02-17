package io.hensu.server.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/// Pool for managing MCP connections.
///
/// Supports two connection modes:
/// - **SSE (Reverse Connect)**: Tenant connects to Hensu via SSE, uses `sse://clientId` format
/// - **HTTP (Outbound)**: Hensu connects to tenant's MCP server via HTTP
///
/// ### SSE Mode
/// When endpoint starts with `sse://`, the connection uses the split-pipe
/// SSE transport where the tenant has already connected to GET /mcp/connect.
///
/// ### Configuration
/// - `hensu.mcp.connection-timeout`: Connection timeout (default: 30s)
/// - `hensu.mcp.read-timeout`: Read timeout (default: 60s)
/// - `hensu.mcp.pool-size`: Max connections per endpoint (default: 10)
@ApplicationScoped
public class McpConnectionPool {

    private static final String SSE_PREFIX = "sse://";

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();
    private final McpConnectionFactory connectionFactory;
    private final McpSessionManager sessionManager;
    private final JsonRpc jsonRpc;
    private final Duration connectionTimeout;
    private final Duration readTimeout;

    @Inject
    public McpConnectionPool(
            McpConnectionFactory connectionFactory,
            McpSessionManager sessionManager,
            JsonRpc jsonRpc,
            @ConfigProperty(name = "hensu.mcp.connection-timeout", defaultValue = "30s")
                    Duration connectionTimeout,
            @ConfigProperty(name = "hensu.mcp.read-timeout", defaultValue = "60s")
                    Duration readTimeout) {
        this.connectionFactory = connectionFactory;
        this.sessionManager = sessionManager;
        this.jsonRpc = jsonRpc;
        this.connectionTimeout = connectionTimeout;
        this.readTimeout = readTimeout;
    }

    /// Gets or creates a connection for the given endpoint.
    ///
    /// For SSE endpoints (sse://clientId), returns an SseMcpConnection
    /// that routes through the McpSessionManager.
    ///
    /// @param endpoint the MCP server endpoint (URL or sse://clientId)
    /// @return the connection, never null
    /// @throws McpException if connection fails
    public McpConnection get(String endpoint) throws McpException {
        // Handle SSE connections
        if (endpoint != null && endpoint.startsWith(SSE_PREFIX)) {
            String clientId = endpoint.substring(SSE_PREFIX.length());
            return getSseConnection(clientId);
        }

        // Handle traditional HTTP connections
        return connections.computeIfAbsent(
                endpoint,
                e -> {
                    try {
                        return connectionFactory.create(e, connectionTimeout, readTimeout);
                    } catch (Exception ex) {
                        throw McpException.connectionFailed(e, ex);
                    }
                });
    }

    /// Gets an SSE connection for a connected client.
    ///
    /// @param clientId the client ID that connected via SSE
    /// @return SSE-based connection
    /// @throws McpException if client is not connected
    public McpConnection getSseConnection(String clientId) throws McpException {
        if (!sessionManager.isConnected(clientId)) {
            throw new McpException("Client not connected via SSE: " + clientId);
        }
        return new SseMcpConnection(clientId, sessionManager, jsonRpc);
    }

    /// Gets a connection for a tenant that uses SSE.
    ///
    /// Convenience method that constructs the sse:// endpoint format.
    ///
    /// @param tenantId the tenant ID
    /// @return SSE-based connection
    /// @throws McpException if tenant is not connected
    public McpConnection getForTenant(String tenantId) throws McpException {
        return get(SSE_PREFIX + tenantId);
    }

    /// Removes and closes a connection.
    ///
    /// @param endpoint the endpoint to disconnect
    public void remove(String endpoint) {
        McpConnection conn = connections.remove(endpoint);
        if (conn != null) {
            conn.close();
        }
    }

    /// Closes all connections and clears the pool.
    public void closeAll() {
        connections.values().forEach(McpConnection::close);
        connections.clear();
    }

    /// Returns the number of active connections.
    ///
    /// @return connection count
    public int size() {
        return connections.size();
    }
}
