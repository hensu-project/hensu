package io.hensu.server.mcp;

import java.time.Duration;

/// Factory for creating MCP connections.
///
/// Implementations handle the specific transport protocol (HTTP, WebSocket, stdio)
/// and connection establishment details.
///
/// @see McpConnection for the connection interface
/// @see McpConnectionPool for connection management
// TODO SSE Protocol?
public interface McpConnectionFactory {

    /// Creates a new connection to an MCP server.
    ///
    /// @param endpoint the MCP server endpoint URL
    /// @param connectionTimeout timeout for establishing connection
    /// @param readTimeout timeout for read operations
    /// @return a connected McpConnection
    /// @throws McpException if connection fails
    McpConnection create(String endpoint, Duration connectionTimeout, Duration readTimeout)
            throws McpException;

    /// Returns whether this factory supports the given endpoint.
    ///
    /// @param endpoint the endpoint URL to check
    /// @return true if this factory can create connections to the endpoint
    default boolean supports(String endpoint) {
        return true;
    }
}
