package io.hensu.server.mcp;

import java.util.List;
import java.util.Map;

/// Interface for MCP server connections.
///
/// Provides methods to interact with an MCP server including:
/// - Listing available tools
/// - Calling tools with arguments
/// - Managing connection lifecycle
///
/// Implementations may use different transport protocols (HTTP, stdio, WebSocket).
///
/// @see McpConnectionPool for connection management
public interface McpConnection {

    /// Lists all tools available on the MCP server.
    ///
    /// @return list of tool descriptors, never null
    /// @throws McpException if the operation fails
    List<McpToolDescriptor> listTools() throws McpException;

    /// Calls a tool with the given arguments.
    ///
    /// @param toolName the name of the tool to call
    /// @param arguments the tool arguments
    /// @return the tool result as a map
    /// @throws McpException if the tool call fails
    Map<String, Object> callTool(String toolName, Map<String, Object> arguments)
            throws McpException;

    /// Returns the endpoint this connection is connected to.
    ///
    /// @return the endpoint URL
    String getEndpoint();

    /// Returns whether this connection is still valid.
    ///
    /// @return true if connected and usable
    boolean isConnected();

    /// Closes this connection and releases resources.
    void close();

    /// Descriptor for an MCP tool.
    ///
    /// @param name the tool name
    /// @param description human-readable description
    /// @param inputSchema JSON schema for input parameters
    record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {}
}
