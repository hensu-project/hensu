package io.hensu.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class McpSidecarTest {

    private McpConnectionPool connectionPool;
    private McpConnection connection;
    private McpSidecar sidecar;

    @BeforeEach
    void setUp() {
        connectionPool = mock(McpConnectionPool.class);
        connection = mock(McpConnection.class);
        sidecar = new McpSidecar(connectionPool);
    }

    @Test
    void shouldHaveMcpHandlerId() {
        assertThat(sidecar.getHandlerId()).isEqualTo("mcp");
    }

    @Nested
    class ExecuteMethod {

        @Test
        void shouldFailWhenNoTenantContext() {
            Map<String, Object> payload = Map.of("tool", "search");
            Map<String, Object> context = Map.of();

            ActionResult result = sidecar.execute(payload, context);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("No tenant context bound");
        }

        @Test
        void shouldFailWhenTenantHasNoMcp() throws Exception {
            TenantInfo tenant = TenantInfo.simple("tenant-1");
            Map<String, Object> payload = Map.of("tool", "search");

            ActionResult result =
                    TenantContext.runAs(tenant, () -> sidecar.execute(payload, Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("no MCP endpoint configured");
        }

        @Test
        void shouldFailWhenToolNameMissing() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local:8080");
            Map<String, Object> payload = Map.of();

            ActionResult result =
                    TenantContext.runAs(tenant, () -> sidecar.execute(payload, Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("Missing or empty 'tool'");
        }

        @Test
        void shouldCallToolSuccessfully() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local:8080");
            Map<String, Object> payload =
                    Map.of("tool", "search", "arguments", Map.of("query", "test"));
            Map<String, Object> toolResult = Map.of("results", "found it");

            when(connectionPool.get("http://mcp.local:8080")).thenReturn(connection);
            when(connection.callTool(eq("search"), any())).thenReturn(toolResult);

            ActionResult result =
                    TenantContext.runAs(tenant, () -> sidecar.execute(payload, Map.of()));

            assertThat(result.success()).isTrue();
            assertThat(result.message()).contains("search");
            assertThat(result.output()).isEqualTo(toolResult);
            verify(connection).callTool("search", Map.of("query", "test"));
        }

        @Test
        void shouldHandleToolCallFailure() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local:8080");
            Map<String, Object> payload = Map.of("tool", "search");

            when(connectionPool.get("http://mcp.local:8080")).thenReturn(connection);
            when(connection.callTool(eq("search"), any()))
                    .thenThrow(new McpException("Tool not found"));

            ActionResult result =
                    TenantContext.runAs(tenant, () -> sidecar.execute(payload, Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("MCP tool call failed");
            assertThat(result.error()).isInstanceOf(McpException.class);
        }

        @Test
        void shouldUseEmptyArgumentsWhenNotProvided() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local:8080");
            Map<String, Object> payload = Map.of("tool", "list");

            when(connectionPool.get("http://mcp.local:8080")).thenReturn(connection);
            when(connection.callTool(eq("list"), any())).thenReturn(Map.of());

            TenantContext.runAs(
                    tenant,
                    () -> {
                        sidecar.execute(payload, Map.of());
                    });

            verify(connection).callTool("list", Map.of());
        }
    }

    @Nested
    class DirectCallMethod {

        @Test
        void shouldCallToolDirectly() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local:8080");
            Map<String, Object> expected = Map.of("data", "value");

            when(connectionPool.get("http://mcp.local:8080")).thenReturn(connection);
            when(connection.callTool("tool", Map.of("arg", "val"))).thenReturn(expected);

            Map<String, Object> result =
                    TenantContext.runAs(
                            tenant, () -> sidecar.callTool("tool", Map.of("arg", "val")));

            assertThat(result).isEqualTo(expected);
        }

        @Test
        void shouldThrowWhenNoTenantContext() {
            assertThatThrownBy(() -> sidecar.callTool("tool", Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No tenant context bound");
        }

        @Test
        void shouldThrowWhenNoMcpConfigured() {
            TenantInfo tenant = TenantInfo.simple("tenant-1");

            TenantContext.runAs(
                    tenant,
                    () -> {
                        assertThatThrownBy(() -> sidecar.callTool("tool", Map.of()))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("no MCP endpoint configured");
                    });
        }
    }

    @Nested
    class ListToolsMethod {

        @Test
        void shouldListTools() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local:8080");
            var tool = new McpConnection.McpToolDescriptor("search", "Search tool", Map.of());

            when(connectionPool.get("http://mcp.local:8080")).thenReturn(connection);
            when(connection.listTools()).thenReturn(java.util.List.of(tool));

            var result = TenantContext.runAs(tenant, () -> sidecar.listTools());

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().name()).isEqualTo("search");
        }

        @Test
        void shouldThrowWhenNoMcpConfigured() {
            TenantInfo tenant = TenantInfo.simple("tenant-1");

            TenantContext.runAs(
                    tenant,
                    () -> {
                        assertThatThrownBy(() -> sidecar.listTools())
                                .isInstanceOf(IllegalStateException.class);
                    });
        }
    }
}
