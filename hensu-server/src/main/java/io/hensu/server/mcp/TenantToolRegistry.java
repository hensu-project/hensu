package io.hensu.server.mcp;

import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolRegistry;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/// Tenant-aware tool registry that combines base tools with MCP-discovered tools.
///
/// Provides a unified view of tools available to the current tenant:
/// - Base tools registered directly (available to all tenants)
/// - MCP tools discovered from tenant's MCP server (tenant-specific)
///
/// ### Tool Precedence
/// If the same tool name exists in both base and MCP registries,
/// the MCP version takes precedence (allows tenant customization).
///
/// ### Thread Safety
/// Thread-safe via ConcurrentHashMap and immutable lists.
///
/// ### Usage
/// {@snippet :
/// // Register base tools (available to all)
/// registry.register(ToolDefinition.simple("search", "Web search"));
///
/// // In tenant context, get combined tools
/// TenantContext.runAs(tenant, () -> {
///     List<ToolDefinition> tools = registry.all(); // Base + MCP tools
/// });
/// }
///
/// @see McpToolDiscovery for MCP tool discovery
/// @see TenantContext for tenant context
@ApplicationScoped
public class TenantToolRegistry implements ToolRegistry {

    private static final Logger LOG = Logger.getLogger(TenantToolRegistry.class);

    private final Map<String, ToolDefinition> baseTools = new ConcurrentHashMap<>();
    private final McpToolDiscovery toolDiscovery;

    public TenantToolRegistry(McpToolDiscovery toolDiscovery) {
        this.toolDiscovery =
                Objects.requireNonNull(toolDiscovery, "toolDiscovery must not be null");
    }

    @Override
    public void register(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        baseTools.put(tool.name(), tool);
        LOG.debugv("Registered base tool: {0}", tool.name());
    }

    @Override
    public Optional<ToolDefinition> get(String name) {
        Objects.requireNonNull(name, "name must not be null");

        // First check MCP tools if in tenant context
        TenantInfo tenant = TenantContext.currentOrNull();
        if (tenant != null && tenant.hasMcp()) {
            try {
                List<ToolDefinition> mcpTools = toolDiscovery.discoverTools();
                Optional<ToolDefinition> mcpTool =
                        mcpTools.stream().filter(t -> t.name().equals(name)).findFirst();
                if (mcpTool.isPresent()) {
                    return mcpTool;
                }
            } catch (McpException e) {
                LOG.warnv(
                        "Failed to fetch MCP tools for tenant {0}: {1}",
                        tenant.tenantId(), e.getMessage());
            }
        }

        // Fall back to base tools
        return Optional.ofNullable(baseTools.get(name));
    }

    @Override
    public List<ToolDefinition> all() {
        Map<String, ToolDefinition> combined = new ConcurrentHashMap<>(baseTools);

        // Add MCP tools if in tenant context (MCP takes precedence)
        TenantInfo tenant = TenantContext.currentOrNull();
        if (tenant != null && tenant.hasMcp()) {
            try {
                List<ToolDefinition> mcpTools = toolDiscovery.discoverTools();
                for (ToolDefinition tool : mcpTools) {
                    combined.put(tool.name(), tool);
                }
            } catch (McpException e) {
                LOG.warnv(
                        "Failed to fetch MCP tools for tenant {0}: {1}",
                        tenant.tenantId(), e.getMessage());
            }
        }

        return List.copyOf(combined.values());
    }

    @Override
    public List<ToolDefinition> forTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        // This method is for explicit tenant queries without context binding
        // Return base tools only; MCP requires actual connection
        LOG.debugv("forTenant({0}) called without MCP - returning base tools only", tenantId);
        return List.copyOf(baseTools.values());
    }

    @Override
    public boolean remove(String name) {
        Objects.requireNonNull(name, "name must not be null");
        ToolDefinition removed = baseTools.remove(name);
        if (removed != null) {
            LOG.debugv("Removed base tool: {0}", name);
            return true;
        }
        return false;
    }

    @Override
    public int size() {
        return all().size();
    }

    /// Returns only the base tools (excluding MCP).
    ///
    /// @return list of base tools, never null
    public List<ToolDefinition> baseTools() {
        return List.copyOf(baseTools.values());
    }

    /// Returns only MCP tools for the current tenant.
    ///
    /// @return list of MCP tools, empty if no tenant or no MCP
    public List<ToolDefinition> mcpTools() {
        TenantInfo tenant = TenantContext.currentOrNull();
        if (tenant == null || !tenant.hasMcp()) {
            return List.of();
        }

        try {
            return toolDiscovery.discoverTools();
        } catch (McpException e) {
            LOG.warnv("Failed to fetch MCP tools: {0}", e.getMessage());
            return List.of();
        }
    }
}
