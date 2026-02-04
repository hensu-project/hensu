package io.hensu.server.mcp;

import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.execution.action.ActionHandler;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Objects;

/// MCP sidecar that bridges MCP tools with the ActionHandler interface.
///
/// Routes tool calls to the appropriate MCP server based on tenant context.
/// Each tenant can have their own MCP server endpoint configured.
///
/// ### Usage
/// Register this handler with ActionExecutor to enable MCP tool calls:
/// {@snippet :
/// actionExecutor.registerHandler(mcpSidecar);
///
/// // DSL: send("mcp", mapOf("tool" to "read_file", "arguments" to mapOf("path" to "/etc/hosts")))
/// }
///
/// ### Tenant Isolation
/// Uses {@link TenantContext} to determine which MCP server to connect to.
/// Each tenant's tools are isolated - one tenant cannot access another's MCP server.
///
/// @see McpConnectionPool for connection management
/// @see TenantContext for tenant context propagation
@ApplicationScoped
public class McpSidecar implements ActionHandler {

    public static final String HANDLER_ID = "mcp";
    public static final String TOOL_KEY = "tool";
    public static final String ARGUMENTS_KEY = "arguments";

    private final McpConnectionPool connectionPool;

    public McpSidecar(McpConnectionPool connectionPool) {
        this.connectionPool =
                Objects.requireNonNull(connectionPool, "connectionPool must not be null");
    }

    @Override
    public String getHandlerId() {
        return HANDLER_ID;
    }

    @Override
    public ActionResult execute(Map<String, Object> payload, Map<String, Object> context) {
        // Validate tenant context
        TenantInfo tenant = TenantContext.currentOrNull();
        if (tenant == null) {
            return ActionResult.failure(
                    "No tenant context bound. MCP calls require tenant context.");
        }

        if (!tenant.hasMcp()) {
            return ActionResult.failure(
                    "Tenant '" + tenant.tenantId() + "' has no MCP endpoint configured.");
        }

        // Extract tool name and arguments from payload
        String toolName = extractToolName(payload);
        if (toolName == null || toolName.isBlank()) {
            return ActionResult.failure("Missing or empty 'tool' in payload.");
        }

        Map<String, Object> arguments = extractArguments(payload);

        // Execute the tool call
        try {
            McpConnection connection = connectionPool.get(tenant.mcpEndpoint());
            Map<String, Object> result = connection.callTool(toolName, arguments);
            return ActionResult.success("Tool '" + toolName + "' executed successfully", result);
        } catch (McpException e) {
            return ActionResult.failure("MCP tool call failed: " + e.getMessage(), e);
        }
    }

    private String extractToolName(Map<String, Object> payload) {
        Object tool = payload.get(TOOL_KEY);
        return tool != null ? tool.toString() : null;
    }

    private Map<String, Object> extractArguments(Map<String, Object> payload) {
        Object args = payload.get(ARGUMENTS_KEY);
        if (args instanceof Map) {
            return (Map<String, Object>) args;
        }
        return Map.of();
    }

    /// Calls an MCP tool directly without going through ActionHandler interface.
    ///
    /// Useful for internal server components that need direct MCP access.
    ///
    /// @param toolName the tool to call
    /// @param arguments the tool arguments
    /// @return the tool result
    /// @throws McpException if the call fails
    /// @throws IllegalStateException if no tenant context or no MCP configured
    public Map<String, Object> callTool(String toolName, Map<String, Object> arguments)
            throws McpException {
        TenantInfo tenant = TenantContext.current();
        if (!tenant.hasMcp()) {
            throw new IllegalStateException(
                    "Tenant '" + tenant.tenantId() + "' has no MCP endpoint configured.");
        }

        McpConnection connection = connectionPool.get(tenant.mcpEndpoint());
        return connection.callTool(toolName, arguments);
    }

    /// Lists available tools from the current tenant's MCP server.
    ///
    /// @return list of available tools
    /// @throws McpException if the listing fails
    /// @throws IllegalStateException if no tenant context or no MCP configured
    public java.util.List<McpConnection.McpToolDescriptor> listTools() throws McpException {
        TenantInfo tenant = TenantContext.current();
        if (!tenant.hasMcp()) {
            throw new IllegalStateException(
                    "Tenant '" + tenant.tenantId() + "' has no MCP endpoint configured.");
        }

        McpConnection connection = connectionPool.get(tenant.mcpEndpoint());
        return connection.listTools();
    }
}
