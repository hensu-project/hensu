package io.hensu.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.tool.ToolDefinition;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpException;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class McpToolDiscoveryTest {

    private McpConnectionPool connectionPool;
    private McpConnection connection;
    private McpToolDiscovery discovery;

    @BeforeEach
    void setUp() {
        connectionPool = mock(McpConnectionPool.class);
        connection = mock(McpConnection.class);
        discovery = new McpToolDiscovery(connectionPool);
    }

    @Nested
    class DiscoverToolsWithTenantContext {

        @Test
        void shouldReturnEmptyWhenTenantHasNoMcp() throws Exception {
            TenantInfo tenant = TenantInfo.simple("tenant-1");

            List<ToolDefinition> result =
                    TenantContext.runAs(tenant, () -> discovery.discoverTools());

            assertThat(result).isEmpty();
        }

        @Test
        void shouldDiscoverToolsFromMcpEndpoint() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local:8080");
            var mcpTool =
                    new McpConnection.McpToolDescriptor(
                            "search",
                            "Search for information",
                            Map.of(
                                    "properties",
                                    Map.of(
                                            "query",
                                            Map.of(
                                                    "type",
                                                    "string",
                                                    "description",
                                                    "Search query")),
                                    "required",
                                    List.of("query")));

            when(connectionPool.get("http://mcp.local:8080")).thenReturn(connection);
            when(connection.listTools()).thenReturn(List.of(mcpTool));

            List<ToolDefinition> result =
                    TenantContext.runAs(tenant, () -> discovery.discoverTools());

            assertThat(result).hasSize(1);
            ToolDefinition tool = result.getFirst();
            assertThat(tool.name()).isEqualTo("search");
            assertThat(tool.description()).isEqualTo("Search for information");
            assertThat(tool.parameters()).hasSize(1);
            assertThat(tool.parameters().getFirst().name()).isEqualTo("query");
            assertThat(tool.parameters().getFirst().required()).isTrue();
        }
    }

    @Nested
    class DiscoverToolsWithExplicitEndpoint {

        @Test
        void shouldDiscoverToolsFromEndpoint() {
            var mcpTool = new McpConnection.McpToolDescriptor("read_file", "Read a file", Map.of());

            when(connectionPool.get("http://mcp.local")).thenReturn(connection);
            when(connection.listTools()).thenReturn(List.of(mcpTool));

            List<ToolDefinition> result = discovery.discoverTools("http://mcp.local");

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().name()).isEqualTo("read_file");
        }

        @Test
        void shouldCacheToolDiscoveryResults() {
            var mcpTool = new McpConnection.McpToolDescriptor("tool", "desc", Map.of());

            when(connectionPool.get("http://mcp.local")).thenReturn(connection);
            when(connection.listTools()).thenReturn(List.of(mcpTool));

            // Call twice
            discovery.discoverTools("http://mcp.local");
            discovery.discoverTools("http://mcp.local");

            // Should only hit MCP once
            verify(connection, times(1)).listTools();
        }

        @Test
        void shouldNotPoisonCacheOnFetchFailure() {
            when(connectionPool.get("http://failing")).thenThrow(new McpException("timeout"));
            when(connectionPool.get("http://healthy")).thenReturn(connection);
            when(connection.listTools())
                    .thenReturn(
                            List.of(new McpConnection.McpToolDescriptor("tool", "desc", Map.of())));

            // Failing endpoint should not block healthy one
            try {
                discovery.discoverTools("http://failing");
            } catch (McpException ignored) {
            }

            List<ToolDefinition> result = discovery.discoverTools("http://healthy");
            assertThat(result).hasSize(1);
            assertThat(discovery.cacheSize()).isEqualTo(1);
        }

        @Test
        void shouldRefetchAfterCacheInvalidation() {
            var mcpTool = new McpConnection.McpToolDescriptor("tool", "desc", Map.of());

            when(connectionPool.get("http://mcp.local")).thenReturn(connection);
            when(connection.listTools()).thenReturn(List.of(mcpTool));

            discovery.discoverTools("http://mcp.local");
            discovery.invalidateCache("http://mcp.local");
            discovery.discoverTools("http://mcp.local");

            verify(connection, times(2)).listTools();
        }
    }

    @Nested
    class CacheManagement {

        @Test
        void shouldReportCacheSize() {
            var mcpTool = new McpConnection.McpToolDescriptor("tool", "desc", Map.of());

            when(connectionPool.get("http://endpoint1")).thenReturn(connection);
            when(connectionPool.get("http://endpoint2")).thenReturn(connection);
            when(connection.listTools()).thenReturn(List.of(mcpTool));

            assertThat(discovery.cacheSize()).isZero();

            discovery.discoverTools("http://endpoint1");
            assertThat(discovery.cacheSize()).isEqualTo(1);

            discovery.discoverTools("http://endpoint2");
            assertThat(discovery.cacheSize()).isEqualTo(2);
        }

        @Test
        void shouldInvalidateAllCaches() {
            var mcpTool = new McpConnection.McpToolDescriptor("tool", "desc", Map.of());

            when(connectionPool.get("http://endpoint1")).thenReturn(connection);
            when(connectionPool.get("http://endpoint2")).thenReturn(connection);
            when(connection.listTools()).thenReturn(List.of(mcpTool));

            discovery.discoverTools("http://endpoint1");
            discovery.discoverTools("http://endpoint2");
            assertThat(discovery.cacheSize()).isEqualTo(2);

            discovery.invalidateAllCaches();
            assertThat(discovery.cacheSize()).isZero();
        }
    }
}
