package io.hensu.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.tool.ToolDefinition;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TenantToolRegistryTest {

    private McpToolDiscovery toolDiscovery;
    private TenantToolRegistry registry;

    @BeforeEach
    void setUp() {
        toolDiscovery = mock(McpToolDiscovery.class);
        registry = new TenantToolRegistry(toolDiscovery);
    }

    @Nested
    class BaseToolRegistration {

        @Test
        void shouldRegisterAndRetrieveBaseTool() {
            ToolDefinition tool = ToolDefinition.simple("search", "Web search");

            registry.register(tool);

            assertThat(registry.get("search")).isPresent();
            assertThat(registry.get("search").get().name()).isEqualTo("search");
        }

        @Test
        void shouldListBaseTools() {
            registry.register(ToolDefinition.simple("tool1", "Tool 1"));
            registry.register(ToolDefinition.simple("tool2", "Tool 2"));

            List<ToolDefinition> tools = registry.baseTools();

            assertThat(tools).hasSize(2);
            assertThat(tools)
                    .extracting(ToolDefinition::name)
                    .containsExactlyInAnyOrder("tool1", "tool2");
        }

        @Test
        void shouldRemoveBaseTool() {
            registry.register(ToolDefinition.simple("search", "Web search"));

            boolean removed = registry.remove("search");

            assertThat(removed).isTrue();
            assertThat(registry.get("search")).isEmpty();
        }

        @Test
        void shouldReturnFalseWhenRemovingNonexistentTool() {
            boolean removed = registry.remove("nonexistent");

            assertThat(removed).isFalse();
        }
    }

    @Nested
    class McpToolIntegration {

        @Test
        void shouldIncludeMcpToolsWhenInTenantContext() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local");
            ToolDefinition mcpTool = ToolDefinition.simple("mcp_tool", "MCP tool");

            registry.register(ToolDefinition.simple("base_tool", "Base tool"));
            when(toolDiscovery.discoverTools()).thenReturn(List.of(mcpTool));

            List<ToolDefinition> tools = TenantContext.runAs(tenant, () -> registry.all());

            assertThat(tools).hasSize(2);
            assertThat(tools)
                    .extracting(ToolDefinition::name)
                    .containsExactlyInAnyOrder("base_tool", "mcp_tool");
        }

        @Test
        void shouldReturnOnlyBaseToolsWithoutTenantContext() {
            registry.register(ToolDefinition.simple("base_tool", "Base tool"));

            List<ToolDefinition> tools = registry.all();

            assertThat(tools).hasSize(1);
            assertThat(tools.getFirst().name()).isEqualTo("base_tool");
        }

        @Test
        void shouldReturnOnlyBaseToolsWhenTenantHasNoMcp() throws Exception {
            TenantInfo tenant = TenantInfo.simple("tenant-1");
            registry.register(ToolDefinition.simple("base_tool", "Base tool"));

            List<ToolDefinition> tools = TenantContext.runAs(tenant, () -> registry.all());

            assertThat(tools).hasSize(1);
            assertThat(tools.getFirst().name()).isEqualTo("base_tool");
        }

        @Test
        void shouldPreferMcpToolOverBaseTool() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local");
            ToolDefinition baseTool = ToolDefinition.simple("search", "Base search");
            ToolDefinition mcpTool = ToolDefinition.simple("search", "MCP search");

            registry.register(baseTool);
            when(toolDiscovery.discoverTools()).thenReturn(List.of(mcpTool));

            List<ToolDefinition> tools = TenantContext.runAs(tenant, () -> registry.all());

            assertThat(tools).hasSize(1);
            assertThat(tools.getFirst().description()).isEqualTo("MCP search");
        }

        @Test
        void shouldGetMcpToolByName() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local");
            ToolDefinition mcpTool = ToolDefinition.simple("mcp_tool", "MCP tool");

            when(toolDiscovery.discoverTools()).thenReturn(List.of(mcpTool));

            var result = TenantContext.runAs(tenant, () -> registry.get("mcp_tool"));

            assertThat(result).isPresent();
            assertThat(result.get().name()).isEqualTo("mcp_tool");
        }

        @Test
        void shouldFallBackToBaseToolWhenMcpToolNotFound() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local");
            registry.register(ToolDefinition.simple("base_tool", "Base tool"));

            when(toolDiscovery.discoverTools()).thenReturn(List.of());

            var result = TenantContext.runAs(tenant, () -> registry.get("base_tool"));

            assertThat(result).isPresent();
            assertThat(result.get().name()).isEqualTo("base_tool");
        }
    }

    @Nested
    class McpToolsMethod {

        @Test
        void shouldReturnMcpToolsOnly() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local");
            ToolDefinition mcpTool = ToolDefinition.simple("mcp_tool", "MCP tool");

            registry.register(ToolDefinition.simple("base_tool", "Base tool"));
            when(toolDiscovery.discoverTools()).thenReturn(List.of(mcpTool));

            List<ToolDefinition> tools = TenantContext.runAs(tenant, () -> registry.mcpTools());

            assertThat(tools).hasSize(1);
            assertThat(tools.getFirst().name()).isEqualTo("mcp_tool");
        }

        @Test
        void shouldReturnEmptyWithoutTenantContext() {
            List<ToolDefinition> tools = registry.mcpTools();

            assertThat(tools).isEmpty();
        }

        @Test
        void shouldReturnEmptyWhenTenantHasNoMcp() throws Exception {
            TenantInfo tenant = TenantInfo.simple("tenant-1");

            List<ToolDefinition> tools = TenantContext.runAs(tenant, () -> registry.mcpTools());

            assertThat(tools).isEmpty();
        }
    }

    @Nested
    class ForTenantMethod {

        @Test
        void shouldReturnBaseToolsOnly() {
            registry.register(ToolDefinition.simple("base_tool", "Base tool"));

            List<ToolDefinition> tools = registry.forTenant("any-tenant");

            assertThat(tools).hasSize(1);
            assertThat(tools.getFirst().name()).isEqualTo("base_tool");
        }
    }

    @Nested
    class SizeMethod {

        @Test
        void shouldReturnTotalToolCount() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local");
            ToolDefinition mcpTool = ToolDefinition.simple("mcp_tool", "MCP tool");

            registry.register(ToolDefinition.simple("base_tool", "Base tool"));
            when(toolDiscovery.discoverTools()).thenReturn(List.of(mcpTool));

            int size = TenantContext.runAs(tenant, () -> registry.size());

            assertThat(size).isEqualTo(2);
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void shouldHandleMcpDiscoveryFailure() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local");
            registry.register(ToolDefinition.simple("base_tool", "Base tool"));

            when(toolDiscovery.discoverTools()).thenThrow(new McpException("Connection failed"));

            // Should not throw, should return base tools
            List<ToolDefinition> tools = TenantContext.runAs(tenant, () -> registry.all());

            assertThat(tools).hasSize(1);
            assertThat(tools.getFirst().name()).isEqualTo("base_tool");
        }

        @Test
        void shouldHandleMcpFailureOnGet() throws Exception {
            TenantInfo tenant = TenantInfo.withMcp("tenant-1", "http://mcp.local");
            registry.register(ToolDefinition.simple("base_tool", "Base tool"));

            when(toolDiscovery.discoverTools()).thenThrow(new McpException("Connection failed"));

            // Should fall back to base tool
            var result = TenantContext.runAs(tenant, () -> registry.get("base_tool"));

            assertThat(result).isPresent();
        }
    }
}
