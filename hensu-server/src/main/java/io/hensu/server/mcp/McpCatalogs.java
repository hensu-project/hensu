package io.hensu.server.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpSchemaConverter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// The tool catalog each tenant's relay published.
///
/// A relay publishes its catalog with a version before it polls, and every poll carries
/// that version; a poll whose version is not the stored one is refused, so a relay that
/// restarted with a different tool set republishes before it takes work. Every node reads
/// the same row, so no node can offer a model a catalog the relay no longer has. Converted
/// definitions are cached per tenant and published content.
///
/// @implNote Thread-safe.
public abstract class McpCatalogs {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /// A published catalog.
    ///
    /// @param version the relay's catalog version, not null
    /// @param tools the published tool descriptors as JSON, not null
    public record Catalog(String version, JsonNode tools) {}

    private record Converted(JsonNode source, List<ToolDefinition> tools) {}

    private final ObjectMapper mapper;
    private final Map<String, Converted> cache = new ConcurrentHashMap<>();

    protected McpCatalogs(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /// Stores a tenant's catalog, replacing the previous one.
    ///
    /// @param tenantId the tenant, not null
    /// @param catalog the catalog, not null
    public abstract void publish(String tenantId, Catalog catalog);

    /// Reads a tenant's catalog.
    ///
    /// @param tenantId the tenant, not null
    /// @return the catalog, or empty when the tenant's relay never published one
    public abstract Optional<Catalog> catalog(String tenantId);

    /// Returns a tenant's tools as the engine's definitions.
    ///
    /// @param tenantId the tenant, not null
    /// @return the tools, empty when none were published, never null
    public List<ToolDefinition> tools(String tenantId) {
        Optional<Catalog> catalog = catalog(tenantId);
        if (catalog.isEmpty()) {
            return List.of();
        }
        // Keyed on the published content, not only its version: a relay that republished
        // different tools under an unchanged version still gets them offered.
        Converted cached = cache.get(tenantId);
        if (cached != null && cached.source().equals(catalog.get().tools())) {
            return cached.tools();
        }
        List<ToolDefinition> tools = new ArrayList<>();
        for (JsonNode tool : catalog.get().tools()) {
            JsonNode schema = tool.path("inputSchema");
            tools.add(
                    McpSchemaConverter.convert(
                            new McpConnection.McpToolDescriptor(
                                    tool.path("name").asText(),
                                    tool.path("description").asText(""),
                                    schema.isObject()
                                            ? mapper.convertValue(schema, MAP_TYPE)
                                            : Map.of())));
        }
        List<ToolDefinition> converted = List.copyOf(tools);
        cache.put(tenantId, new Converted(catalog.get().tools(), converted));
        return converted;
    }
}
