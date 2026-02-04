package io.hensu.server.mcp;

import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolDefinition.ParameterDef;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/// Discovers and caches tools from MCP servers.
///
/// Connects to tenant MCP servers to list available tools and converts
/// MCP tool descriptors to Hensu ToolDefinition objects. Tool lists are
/// cached per endpoint to avoid repeated discovery calls.
///
/// ### Usage
/// {@snippet :
/// // Discover tools for current tenant
/// List<ToolDefinition> tools = mcpToolDiscovery.discoverTools();
///
/// // Force refresh
/// mcpToolDiscovery.invalidateCache(endpoint);
/// List<ToolDefinition> freshTools = mcpToolDiscovery.discoverTools();
/// }
///
/// ### Caching
/// Tool lists are cached per MCP endpoint. Use {@link #invalidateCache(String)}
/// to force re-discovery when tools change.
///
/// @see McpConnectionPool for connection management
/// @see TenantContext for tenant-scoped tool access
@ApplicationScoped
public class McpToolDiscovery {

    private static final Logger LOG = Logger.getLogger(McpToolDiscovery.class);

    private final McpConnectionPool connectionPool;
    private final Map<String, List<ToolDefinition>> toolCache = new ConcurrentHashMap<>();

    public McpToolDiscovery(McpConnectionPool connectionPool) {
        this.connectionPool =
                Objects.requireNonNull(connectionPool, "connectionPool must not be null");
    }

    /// Discovers tools from the current tenant's MCP server.
    ///
    /// Results are cached. Use {@link #invalidateCache(String)} to force refresh.
    ///
    /// @return list of discovered tools, never null (may be empty)
    /// @throws IllegalStateException if no tenant context or no MCP configured
    /// @throws McpException if tool discovery fails
    public List<ToolDefinition> discoverTools() throws McpException {
        TenantInfo tenant = TenantContext.current();
        if (!tenant.hasMcp()) {
            LOG.debugv("Tenant {0} has no MCP endpoint, returning empty tools", tenant.tenantId());
            return List.of();
        }
        return discoverTools(tenant.mcpEndpoint());
    }

    /// Discovers tools from a specific MCP endpoint.
    ///
    /// @param endpoint the MCP server endpoint
    /// @return list of discovered tools, never null
    /// @throws McpException if tool discovery fails
    public List<ToolDefinition> discoverTools(String endpoint) throws McpException {
        Objects.requireNonNull(endpoint, "endpoint must not be null");

        return toolCache.computeIfAbsent(endpoint, this::fetchAndConvert);
    }

    /// Invalidates the tool cache for the given endpoint.
    ///
    /// The next call to {@link #discoverTools(String)} will re-fetch from the server.
    ///
    /// @param endpoint the MCP endpoint to invalidate
    public void invalidateCache(String endpoint) {
        toolCache.remove(endpoint);
        LOG.debugv("Invalidated tool cache for endpoint: {0}", endpoint);
    }

    /// Invalidates all cached tool lists.
    public void invalidateAllCaches() {
        toolCache.clear();
        LOG.debug("Invalidated all tool caches");
    }

    /// Returns the number of cached endpoints.
    ///
    /// @return cache size
    public int cacheSize() {
        return toolCache.size();
    }

    private List<ToolDefinition> fetchAndConvert(String endpoint) {
        try {
            LOG.infov("Discovering tools from MCP endpoint: {0}", endpoint);
            McpConnection connection = connectionPool.get(endpoint);
            List<McpConnection.McpToolDescriptor> mcpTools = connection.listTools();

            List<ToolDefinition> tools = new ArrayList<>(mcpTools.size());
            for (McpConnection.McpToolDescriptor mcpTool : mcpTools) {
                tools.add(convert(mcpTool));
            }

            LOG.infov("Discovered {0} tools from {1}", tools.size(), endpoint);
            return List.copyOf(tools);
        } catch (McpException e) {
            LOG.errorv(e, "Failed to discover tools from {0}", endpoint);
            throw new RuntimeException("Tool discovery failed: " + e.getMessage(), e);
        }
    }

    /// Converts an MCP tool descriptor to a Hensu ToolDefinition.
    ///
    /// @param mcpTool the MCP tool descriptor
    /// @return the converted tool definition
    static ToolDefinition convert(McpConnection.McpToolDescriptor mcpTool) {
        List<ParameterDef> parameters = extractParameters(mcpTool.inputSchema());
        return new ToolDefinition(mcpTool.name(), mcpTool.description(), parameters, null);
    }

    /// Extracts parameter definitions from MCP JSON Schema.
    ///
    /// MCP tools use JSON Schema for input parameters. This method extracts
    /// the relevant parts to create ParameterDef objects.
    private static List<ParameterDef> extractParameters(Map<String, Object> inputSchema) {
        if (inputSchema == null) {
            return List.of();
        }

        Object properties = inputSchema.get("properties");
        if (!(properties instanceof Map)) {
            return List.of();
        }

        Map<String, Object> propsMap = (Map<String, Object>) properties;
        List<String> required = extractRequiredList(inputSchema);

        List<ParameterDef> params = new ArrayList<>();
        for (Map.Entry<String, Object> entry : propsMap.entrySet()) {
            String paramName = entry.getKey();
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> paramSchema = (Map<String, Object>) entry.getValue();

            String type = getString(paramSchema, "type", "string");
            String description = getString(paramSchema, "description", "");
            boolean isRequired = required.contains(paramName);
            Object defaultValue = paramSchema.get("default");

            params.add(new ParameterDef(paramName, type, description, isRequired, defaultValue));
        }

        return params;
    }

    private static List<String> extractRequiredList(Map<String, Object> schema) {
        Object required = schema.get("required");
        if (required instanceof List) {
            return ((List<?>) required)
                    .stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
