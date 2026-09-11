package io.hensu.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpException;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class McpToolProviderTest {

    private static final String ENDPOINT = "sse://tenant-1";

    private McpToolDiscovery discovery;
    private McpConnectionPool connectionPool;
    private McpConnection connection;
    private McpToolProvider provider;

    @BeforeEach
    void setUp() {
        discovery = mock(McpToolDiscovery.class);
        connectionPool = mock(McpConnectionPool.class);
        connection = mock(McpConnection.class);
        provider = new McpToolProvider(discovery, connectionPool, Duration.ofSeconds(5));
    }

    private static TenantInfo withMcp() {
        return TenantInfo.withMcp("tenant-1", ENDPOINT);
    }

    @Nested
    class Catalog {

        @Test
        void shouldExposeTenantToolsWhenMcpIsConfigured() throws Exception {
            when(discovery.discoverTools())
                    .thenReturn(List.of(ToolDefinition.simple("search", "Search")));

            List<ToolDefinition> tools = TenantContext.runAs(withMcp(), () -> provider.tools());

            assertThat(tools).singleElement().extracting(ToolDefinition::name).isEqualTo("search");
        }

        /// The startup duplicate check reads every provider's catalog before any
        /// request binds a tenant. Throwing there would abort boot.
        @Test
        void shouldExposeNoToolsOutsideATenant() {
            assertThat(provider.tools()).isEmpty();
        }

        @Test
        void shouldExposeNoToolsWhenTenantHasNoMcpEndpoint() throws Exception {
            List<ToolDefinition> tools =
                    TenantContext.runAs(TenantInfo.simple("tenant-1"), () -> provider.tools());

            assertThat(tools).isEmpty();
        }

        /// The prod bug: a provider that throws from tools() is treated as absent by
        /// the router, so one unreachable MCP server must degrade to an empty
        /// catalog, not to an exception crossing the seam.
        @Test
        void shouldReportEmptyCatalogWhenDiscoveryFails() throws Exception {
            when(discovery.discoverTools()).thenThrow(new McpException("endpoint unreachable"));

            List<ToolDefinition> tools = TenantContext.runAs(withMcp(), () -> provider.tools());

            assertThat(tools).isEmpty();
        }
    }

    @Nested
    class Invocation {

        @Test
        void shouldRenderToolOutputAsText() throws Exception {
            when(connectionPool.get(ENDPOINT)).thenReturn(connection);
            when(connection.callTool(anyString(), anyMap()))
                    .thenReturn(
                            Map.of(
                                    "content",
                                    List.of(Map.of("type", "text", "text", "file contents"))));

            ToolCallResult result =
                    TenantContext.runAs(
                            withMcp(),
                            () ->
                                    provider.call(
                                            "read_file",
                                            Map.of("path", "/etc/hosts"),
                                            new HashMap<>()));

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(result.output()).isEqualTo("file contents");
        }

        @Test
        void shouldMapProtocolErrorsOntoFailure() throws Exception {
            when(connectionPool.get(ENDPOINT)).thenReturn(connection);
            when(connection.callTool(anyString(), anyMap()))
                    .thenThrow(new McpException("client not connected"));

            ToolCallResult result =
                    TenantContext.runAs(
                            withMcp(), () -> provider.call("read_file", Map.of(), new HashMap<>()));

            assertThat(result.status()).isEqualTo(ToolCallStatus.FAILURE);
            assertThat(result.error()).contains("client not connected");
        }

        @Test
        void shouldFailWithoutTenantContext() {
            ToolCallResult result = provider.call("read_file", Map.of(), new HashMap<>());

            assertThat(result.status()).isEqualTo(ToolCallStatus.FAILURE);
            assertThat(result.error()).contains("tenant context");
        }

        /// The SSE transport waits for a response indefinitely and the engine runs
        /// no watchdog, so without the provider's own deadline an unresponsive MCP
        /// server parks a workflow thread forever.
        @Test
        void shouldReportTimeoutWhenTheServerNeverAnswers() throws Exception {
            CountDownLatch released = new CountDownLatch(1);
            McpToolProvider impatient =
                    new McpToolProvider(discovery, connectionPool, Duration.ofMillis(150));

            when(connectionPool.get(ENDPOINT)).thenReturn(connection);
            when(connection.callTool(anyString(), anyMap()))
                    .thenAnswer(
                            _ -> {
                                released.await(10, TimeUnit.SECONDS);
                                return Map.of();
                            });

            ToolCallResult result;
            try {
                result =
                        TenantContext.runAs(
                                withMcp(),
                                () -> impatient.call("slow_tool", Map.of(), new HashMap<>()));
            } finally {
                released.countDown();
            }

            assertThat(result.status()).isEqualTo(ToolCallStatus.TIMEOUT);
            assertThat(result.error()).contains("slow_tool");
        }
    }
}
