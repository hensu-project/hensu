package io.hensu.server.mcp;

import io.hensu.core.tool.ToolDefinition;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpException;
import io.hensu.mcp.McpSchemaConverter;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
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
/// Tool lists are cached per MCP endpoint for the lifetime of the client's
/// session. The only event that can change a tenant's tools is its relay going
/// away and coming back, so the cache entry is dropped whenever the session
/// registers or ends (see {@link #onSessionChanged(McpSessionChanged)}): a
/// reconnecting client's list is authoritative, and a client that never returns
/// leaves no entry behind. Use {@link #invalidateCache(String)} to force
/// re-discovery outside that cycle.
///
/// @see McpConnections for how a connection is opened
/// @see McpSchemaConverter for the MCP-to-Hensu schema translation
/// @see TenantContext for tenant-scoped tool access
@ApplicationScoped
public class McpToolDiscovery {

    private static final Logger LOG = Logger.getLogger(McpToolDiscovery.class);

    private final McpConnections connections;
    private final Map<String, List<ToolDefinition>> toolCache = new ConcurrentHashMap<>();

    /// The fetch in flight for each endpoint, keyed by endpoint. An invalidation
    /// removes the endpoint's entry, so a fetch that straddles one finds its
    /// token gone and knows its result may describe a session that no longer
    /// exists. Invalidating one endpoint leaves every other endpoint's fetch
    /// alone, and an entry lives only as long as its fetch.
    private final Map<String, Object> fetches = new ConcurrentHashMap<>();

    public McpToolDiscovery(McpConnections connections) {
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
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

        List<ToolDefinition> cached = toolCache.get(endpoint);
        if (cached != null) {
            return cached;
        }
        Object fetch = new Object();
        fetches.put(endpoint, fetch);
        List<ToolDefinition> fetched;
        try {
            fetched = fetchAndConvert(endpoint);
        } catch (RuntimeException e) {
            fetches.remove(endpoint, fetch);
            throw e;
        }
        List<ToolDefinition> winner = toolCache.putIfAbsent(endpoint, fetched);
        boolean current = fetches.remove(endpoint, fetch);
        if (winner != null) {
            return winner;
        }
        // An invalidation of this endpoint that ran while this fetch was in flight may
        // have preceded the put, in which case the entry just written can describe the
        // session the invalidation retired. Withdraw it: this caller uses its copy once
        // and the next caller re-lists. An invalidation that follows the token check
        // removes the entry itself. A concurrent fetch of the same endpoint also takes
        // the token, which at worst withdraws a fresh entry and costs one re-list.
        if (!current) {
            toolCache.remove(endpoint, fetched);
        }
        return fetched;
    }

    /// Invalidates the tool cache for the given endpoint.
    ///
    /// The next call to {@link #discoverTools(String)} will re-fetch from the server.
    ///
    /// @param endpoint the MCP endpoint to invalidate
    public void invalidateCache(String endpoint) {
        fetches.remove(endpoint);
        toolCache.remove(endpoint);
        LOG.debugv("Invalidated tool cache for endpoint: {0}", endpoint);
    }

    /// Drops the cached catalog of a client whose session began or ended.
    ///
    /// Runs synchronously on the thread that changed the session, usually the
    /// Vert.x event loop; it only removes a map entry and never blocks.
    ///
    /// @param event the session change, not null
    void onSessionChanged(@Observes McpSessionChanged event) {
        invalidateCache(McpConnections.endpointFor(event.clientId()));
    }

    /// Invalidates all cached tool lists.
    public void invalidateAllCaches() {
        fetches.clear();
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
            McpConnection connection = connections.get(endpoint);
            List<McpConnection.McpToolDescriptor> mcpTools = connection.listTools();

            List<ToolDefinition> tools = new ArrayList<>(mcpTools.size());
            for (McpConnection.McpToolDescriptor mcpTool : mcpTools) {
                tools.add(McpSchemaConverter.convert(mcpTool));
            }

            LOG.infov("Discovered {0} tools from {1}", tools.size(), endpoint);
            return List.copyOf(tools);
        } catch (McpException e) {
            LOG.warnv("Failed to discover tools from {0}: {1}", endpoint, e.getMessage());
            throw e;
        }
    }
}
