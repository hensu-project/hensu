package io.hensu.server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.server.mcp.McpCatalogs;
import java.util.Optional;
import javax.sql.DataSource;

/// PostgreSQL storage of the catalogs tenants' relays publish.
public final class JdbcMcpCatalogs extends McpCatalogs {

    private static final String SQL_PUBLISH =
            """
            INSERT INTO runtime.mcp_catalogs (tenant_id, version, tools, updated_at)
            VALUES (?, ?, ?::jsonb, now())
            ON CONFLICT (tenant_id) DO UPDATE
               SET version = EXCLUDED.version, tools = EXCLUDED.tools, updated_at = now()
            """;

    private static final String SQL_READ =
            "SELECT version, tools::text AS tools FROM runtime.mcp_catalogs WHERE tenant_id = ?";

    private final JdbcSupport jdbc;
    private final ObjectMapper mapper;

    public JdbcMcpCatalogs(DataSource dataSource, ObjectMapper mapper) {
        super(mapper);
        this.jdbc = new JdbcSupport(dataSource);
        this.mapper = mapper;
    }

    @Override
    public void publish(String tenantId, Catalog catalog) {
        jdbc.update(
                SQL_PUBLISH,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, catalog.version());
                    ps.setString(3, catalog.tools().toString());
                },
                "Failed to publish the catalog of " + tenantId);
    }

    @Override
    public Optional<Catalog> catalog(String tenantId) {
        return jdbc.queryOne(
                SQL_READ,
                ps -> ps.setString(1, tenantId),
                rs -> {
                    try {
                        return new Catalog(
                                rs.getString("version"), mapper.readTree(rs.getString("tools")));
                    } catch (Exception e) {
                        throw new PersistenceException("Unreadable catalog of " + tenantId, e);
                    }
                },
                "Failed to read the catalog of " + tenantId);
    }
}
