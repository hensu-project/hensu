package io.hensu.server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.server.mcp.McpCatalogs;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// Single-process storage of the catalogs tenants' relays publish.
public final class InMemoryMcpCatalogs extends McpCatalogs {

    private final Map<String, Catalog> catalogs = new ConcurrentHashMap<>();

    public InMemoryMcpCatalogs(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public void publish(String tenantId, Catalog catalog) {
        catalogs.put(tenantId, catalog);
    }

    @Override
    public Optional<Catalog> catalog(String tenantId) {
        return Optional.ofNullable(catalogs.get(tenantId));
    }
}
