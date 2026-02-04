package io.hensu.server.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/// MCP connection implementation using SSE split-pipe transport.
///
/// This implementation routes tool calls through the {@link McpSessionManager}
/// which sends requests via SSE and receives responses via HTTP POST.
///
/// ### Virtual Thread Blocking
/// Uses `.await().indefinitely()` to block virtual threads efficiently.
/// In Java 25, this is the recommended pattern - virtual threads make
/// blocking I/O cheap, allowing imperative code style with reactive I/O.
///
/// ### Usage
/// ```java
/// SseMcpConnection conn = new SseMcpConnection("tenant-123", sessionManager, jsonRpc);
/// Map<String, Object> result = conn.callTool("read_file", Map.of("path", "/etc/hosts"));
/// ```
///
/// @see McpSessionManager for SSE transport management
/// @see io.hensu.server.api.McpGatewayResource for HTTP endpoints
public class SseMcpConnection implements McpConnection {

    private final String clientId;
    private final McpSessionManager sessionManager;
    private final JsonRpc jsonRpc;

    /// Creates a new SSE-based MCP connection.
    ///
    /// @param clientId the client/tenant ID connected via SSE
    /// @param sessionManager the session manager handling SSE transport
    /// @param jsonRpc JSON-RPC helper for parsing responses
    public SseMcpConnection(String clientId, McpSessionManager sessionManager, JsonRpc jsonRpc) {
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.sessionManager =
                Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.jsonRpc = Objects.requireNonNull(jsonRpc, "jsonRpc must not be null");
    }

    @Override
    public List<McpToolDescriptor> listTools() throws McpException {
        if (!sessionManager.isConnected(clientId)) {
            throw new McpException("Client not connected: " + clientId);
        }

        // Send tools/list request via SSE and wait for response
        String response =
                sessionManager.sendRequest(clientId, "tools/list", Map.of()).await().indefinitely();

        Map<String, Object> result = jsonRpc.parseResult(response);

        // Parse tools from result
        List<Map<String, Object>> toolsList = (List<Map<String, Object>>) result.get("tools");
        if (toolsList == null) {
            return List.of();
        }

        return toolsList.stream().map(this::parseToolDescriptor).toList();
    }

    @Override
    public Map<String, Object> callTool(String toolName, Map<String, Object> arguments)
            throws McpException {
        if (!sessionManager.isConnected(clientId)) {
            throw new McpException("Client not connected: " + clientId);
        }

        // Build MCP tools/call params
        Map<String, Object> params =
                Map.of("name", toolName, "arguments", arguments != null ? arguments : Map.of());

        // Send request via SSE and BLOCK the virtual thread until response
        // This is efficient in Java 25 - the OS thread is released while waiting
        String response =
                sessionManager.sendRequest(clientId, "tools/call", params).await().indefinitely();

        // Parse and return the result
        return jsonRpc.parseResult(response);
    }

    @Override
    public String getEndpoint() {
        return "sse://" + clientId;
    }

    @Override
    public boolean isConnected() {
        return sessionManager.isConnected(clientId);
    }

    @Override
    public void close() {
        sessionManager.disconnect(clientId);
    }

    /// Parses a tool descriptor from the MCP response format.
    private McpToolDescriptor parseToolDescriptor(Map<String, Object> toolMap) {
        String name = (String) toolMap.get("name");
        String description = (String) toolMap.getOrDefault("description", "");
        Map<String, Object> inputSchema = (Map<String, Object>) toolMap.get("inputSchema");
        return new McpToolDescriptor(
                name, description, inputSchema != null ? inputSchema : Map.of());
    }
}
