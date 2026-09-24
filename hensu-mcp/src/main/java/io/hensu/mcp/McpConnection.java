package io.hensu.mcp;

import java.util.List;
import java.util.Map;

/// Interface for MCP server connections.
///
/// Provides methods to interact with an MCP server including:
/// - Listing available tools
/// - Calling tools with arguments
/// - Managing connection lifecycle
///
/// Three transports implement this interface, and which ones exist is a
/// property of the runtime rather than of the protocol:
///
/// - **stdio** (CLI) – a server process Hensu launched and contains
/// - **Streamable HTTP** (CLI) – a remote endpoint Hensu dials out to
/// - **split pipe** (server) – a session the tenant opened inbound, which the
///   server answers over without ever dialling out
///
/// Each runtime owns its own lifetime policy; this interface only describes
/// what a live connection can do.
///
/// @see McpResultRenderer for turning a response into an agent-readable result
public interface McpConnection {

    /// Lists all tools available on the MCP server.
    ///
    /// @return list of tool descriptors, never null
    /// @throws McpException if the operation fails
    List<McpToolDescriptor> listTools() throws McpException;

    /// Calls a tool with the given arguments.
    ///
    /// @param toolName the name of the tool to call, not null
    /// @param arguments the tool arguments, not null (may be empty) – the agent
    ///     loop and the action handler both normalize an absent argument object to
    ///     an empty map before a connection is reached
    /// @return the tool result as a map
    /// @throws McpException if the tool call fails
    Map<String, Object> callTool(String toolName, Map<String, Object> arguments)
            throws McpException;

    /// Returns the endpoint handle this connection is bound to.
    ///
    /// The shape is transport-specific and is a URL for only one of them:
    /// `stdio:command`, an `https://` URL, or `sse://clientId`.
    ///
    /// @return the endpoint handle, never null
    String getEndpoint();

    /// Returns whether this connection is still valid.
    ///
    /// @return true if connected and usable
    boolean isConnected();

    /// Closes this connection and releases resources.
    void close();

    /// Returns why tools this server knows of are missing from its catalog.
    ///
    /// A connection may leave a tool out for a reason only it can see – a
    /// definition the transport's own rules refuse, or a server that declined to
    /// list anything. The runtime that adopted the connection decides where an
    /// operator reads these; the CLI, whose console log is off, prints them
    /// beside the run's other tool-source notices.
    ///
    /// @return the reasons, in the order they were found, never null (empty by default)
    default List<String> catalogNotices() {
        return List.of();
    }

    /// Descriptor for an MCP tool.
    ///
    /// @param name the tool name
    /// @param description human-readable description
    /// @param inputSchema JSON schema for input parameters
    record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {}
}
