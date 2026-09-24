package io.hensu.cli.tool;

import io.hensu.core.tool.ToolDefinition;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpConnection.McpToolDescriptor;
import io.hensu.mcp.McpSchemaConverter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/// The tools every running MCP server publishes, under the names agents see.
///
/// Assembles one run's catalog across servers: applies each declaration's
/// `prefix:`, refuses a name that is not a legal tool name once prefixed, and
/// keeps the first server to publish a name when two collide. Each refusal is
/// reported, never silent, because a tool that quietly fails to appear is
/// indistinguishable from one the server never had.
///
/// @implNote **Immutable.** Every change returns a new catalog, so a reader
///     holding one sees a consistent pair of tools and routes while the provider
///     swaps in the next.
final class McpToolCatalog {

    /// The catalog before any server has been adopted.
    static final McpToolCatalog EMPTY = new McpToolCatalog(Map.of(), Map.of());

    private final Map<String, ToolDefinition> tools;
    private final Map<String, McpRoute> routes;

    private McpToolCatalog(Map<String, ToolDefinition> tools, Map<String, McpRoute> routes) {
        this.tools = tools;
        this.routes = routes;
    }

    /// Adds one server's tools under its prefix.
    ///
    /// @param route the server the tools belong to, not null
    /// @param descriptors the tools it listed, not null
    /// @param refusals receives one operator-facing reason per tool left out, not null
    /// @return the catalog with the server's accepted tools added, never null
    McpToolCatalog with(
            McpRoute route, List<McpToolDescriptor> descriptors, Consumer<String> refusals) {
        String prefix = route.spec().prefix();
        Map<String, ToolDefinition> nextTools = new LinkedHashMap<>(tools);
        Map<String, McpRoute> nextRoutes = new LinkedHashMap<>(routes);
        for (McpToolDescriptor descriptor : descriptors) {
            String published = prefix + descriptor.name();
            if (!McpConfig.TOOL_NAME.matcher(published).matches()) {
                refusals.accept(
                        "MCP server '"
                                + route.name()
                                + "' publishes '"
                                + descriptor.name()
                                + "', which is not a legal tool name"
                                + (prefix.isEmpty() ? "" : " once prefixed with '" + prefix + "'")
                                + "; it is not offered to agents");
                continue;
            }
            if (nextRoutes.containsKey(published)) {
                refusals.accept(
                        "MCP server '"
                                + route.name()
                                + "' publishes '"
                                + published
                                + "', which another declared server already offers; the later one"
                                + " is ignored. Declare a prefix: on one of them to keep both.");
                continue;
            }
            nextTools.put(published, rename(McpSchemaConverter.convert(descriptor), published));
            nextRoutes.put(published, route);
        }
        return new McpToolCatalog(Map.copyOf(nextTools), Map.copyOf(nextRoutes));
    }

    /// Drops every tool one server published.
    ///
    /// @param connection the server that stopped, not null
    /// @return the catalog without its tools, never null
    McpToolCatalog without(McpConnection connection) {
        Map<String, McpRoute> nextRoutes = new LinkedHashMap<>(routes);
        nextRoutes.values().removeIf(route -> route.connection() == connection);
        Map<String, ToolDefinition> nextTools = new LinkedHashMap<>(tools);
        nextTools.keySet().retainAll(nextRoutes.keySet());
        return new McpToolCatalog(Map.copyOf(nextTools), Map.copyOf(nextRoutes));
    }

    /// Returns every published tool.
    ///
    /// @return the tools under their published names, never null (may be empty)
    List<ToolDefinition> tools() {
        return List.copyOf(tools.values());
    }

    /// Returns the server that published a name.
    ///
    /// @param toolName the published name, not null
    /// @return the owning route, or null when no server publishes the name
    McpRoute route(String toolName) {
        return routes.get(toolName);
    }

    /// Republishes a discovered tool under its prefixed name.
    ///
    /// The prefix is a property of the catalog this run assembles, not of the
    /// tool the server published, so the rename happens here rather than in the
    /// schema converter, which both runtimes share.
    private static ToolDefinition rename(ToolDefinition tool, String published) {
        return published.equals(tool.name())
                ? tool
                : new ToolDefinition(
                        published,
                        tool.description(),
                        tool.parameters(),
                        tool.returnType(),
                        tool.rawSchema());
    }
}
