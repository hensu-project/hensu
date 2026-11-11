package io.hensu.mcp;

import io.hensu.core.tool.ToolDefinition;
import io.hensu.mcp.McpConnection.McpToolDescriptor;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/// The tools every running MCP server publishes, under the names agents see.
///
/// Assembles one catalog across servers: applies each declaration's `tools:`
/// filter and `prefix:`, refuses a name that is not a legal tool name once
/// prefixed, and keeps the first server to publish a name when two collide. Each
/// refusal is reported, never silent, because a tool that quietly fails to
/// appear is indistinguishable from one the server never had. A tool the filter
/// leaves out is not a refusal: the operator chose it.
///
/// The CLI and the relay assemble their catalogs here so the two cannot
/// disagree about which tools a declaration publishes. Each keeps its own
/// {@link Route} type – what it needs to know about a running server beyond its
/// declaration and connection – which is why the catalog is generic over it.
///
/// @param <R> the runtime's route to a running server
/// @implNote **Immutable after construction.** Every change returns a new
///     catalog, so a reader holding one sees a consistent set of tools, descriptors
///     and routes while its owner swaps in the next.
public final class McpToolCatalog<R extends McpToolCatalog.Route> {

    /// Shape a published tool name, and therefore a prefix, has to keep.
    public static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.\\-]+");

    private static final McpToolCatalog<?> EMPTY =
            new McpToolCatalog<>(Map.of(), Map.of(), Map.of());

    private final Map<String, ToolDefinition> tools;
    private final Map<String, McpToolDescriptor> descriptors;
    private final Map<String, R> routes;

    private McpToolCatalog(
            Map<String, ToolDefinition> tools,
            Map<String, McpToolDescriptor> descriptors,
            Map<String, R> routes) {
        this.tools = tools;
        this.descriptors = descriptors;
        this.routes = routes;
    }

    /// A running MCP server as the catalog sees it.
    ///
    /// @see McpServerSpec for the declaration a route carries
    public interface Route {

        /// Returns the declaration the server was started from.
        ///
        /// @return the declaration, never null
        McpServerSpec spec();

        /// Returns the open connection to the server.
        ///
        /// @return the connection, never null
        McpConnection connection();

        /// Returns the declared server name.
        ///
        /// @return the name, never null
        default String name() {
            return spec().name();
        }

        /// Maps a published name back to the name its server knows.
        ///
        /// @param published the name as published to agents, not null
        /// @return the name without this server's prefix, never null
        default String serverSideName(String published) {
            String prefix = spec().prefix();
            return prefix.isEmpty() || !published.startsWith(prefix)
                    ? published
                    : published.substring(prefix.length());
        }
    }

    /// Returns the catalog before any server has been adopted.
    ///
    /// @param <R> the runtime's route type
    /// @return the empty catalog, never null
    @SuppressWarnings("unchecked")
    public static <R extends Route> McpToolCatalog<R> empty() {
        return (McpToolCatalog<R>) EMPTY;
    }

    /// Adds one server's tools under its filter and prefix.
    ///
    /// `tools:` names a server's tools as the server lists them, before
    /// `prefix:`. A filter entry written in the published form instead – the
    /// prefix followed by a name the server does list – is read the way that
    /// cannot widen what agents see: in `deny:` it also denies the listed tool,
    /// in `allow:` it allows nothing. Either way the operator is told the
    /// spelling the filter expects, because a `deny:` that silently matched
    /// nothing would publish a tool the operator believes is fenced off.
    ///
    /// @param route the server the tools belong to, not null
    /// @param listed the tools it listed, not null
    /// @param refusals receives one operator-facing reason per tool left out
    ///     against the declaration's intent, per allowed name the server does not
    ///     list, and per filter entry written with the prefix, not null
    /// @return the catalog with the server's accepted tools added, never null
    public McpToolCatalog<R> with(
            R route, List<McpToolDescriptor> listed, Consumer<String> refusals) {
        String prefix = route.spec().prefix();
        McpServerSpec.ToolFilter filter = route.spec().tools();
        Map<String, ToolDefinition> nextTools = new LinkedHashMap<>(tools);
        Map<String, McpToolDescriptor> nextDescriptors = new LinkedHashMap<>(descriptors);
        Map<String, R> nextRoutes = new LinkedHashMap<>(routes);
        Set<String> seen = new HashSet<>();
        listed.forEach(descriptor -> seen.add(descriptor.name()));
        Set<String> deniedAsPublished = new HashSet<>();
        for (String name : filter.deny()) {
            String meant = meantBeforePrefix(name, prefix, seen);
            if (meant != null) {
                deniedAsPublished.add(meant);
                refusals.accept(
                        misspelled(
                                route, "deny", name, meant, "The tool is withheld all the same."));
            }
        }
        for (McpToolDescriptor descriptor : listed) {
            if (!filter.admits(descriptor.name())
                    || deniedAsPublished.contains(descriptor.name())) {
                continue;
            }
            String published = prefix + descriptor.name();
            if (!TOOL_NAME.matcher(published).matches()) {
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
            McpToolDescriptor renamed =
                    new McpToolDescriptor(
                            published, descriptor.description(), descriptor.inputSchema());
            nextTools.put(published, rename(McpSchemaConverter.convert(descriptor), published));
            nextDescriptors.put(published, renamed);
            nextRoutes.put(published, route);
        }
        filter.allow().stream()
                .filter(name -> !seen.contains(name))
                .sorted()
                .forEach(
                        name -> {
                            String meant = meantBeforePrefix(name, prefix, seen);
                            refusals.accept(
                                    meant != null
                                            ? misspelled(
                                                    route,
                                                    "allow",
                                                    name,
                                                    meant,
                                                    "Until then the tool is not offered.")
                                            : "MCP server '"
                                                    + route.name()
                                                    + "' declares tools.allow '"
                                                    + name
                                                    + "', which the server does not list");
                        });
        return new McpToolCatalog<>(
                Map.copyOf(nextTools), Map.copyOf(nextDescriptors), Map.copyOf(nextRoutes));
    }

    /// Drops every tool one server published.
    ///
    /// @param connection the server that stopped, not null
    /// @return the catalog without its tools, never null
    public McpToolCatalog<R> without(McpConnection connection) {
        Map<String, R> nextRoutes = new LinkedHashMap<>(routes);
        nextRoutes.values().removeIf(route -> route.connection() == connection);
        Map<String, ToolDefinition> nextTools = new LinkedHashMap<>(tools);
        nextTools.keySet().retainAll(nextRoutes.keySet());
        Map<String, McpToolDescriptor> nextDescriptors = new LinkedHashMap<>(descriptors);
        nextDescriptors.keySet().retainAll(nextRoutes.keySet());
        return new McpToolCatalog<>(
                Map.copyOf(nextTools), Map.copyOf(nextDescriptors), Map.copyOf(nextRoutes));
    }

    /// Returns every published tool.
    ///
    /// @return the tools under their published names, never null (may be empty)
    public List<ToolDefinition> tools() {
        return List.copyOf(tools.values());
    }

    /// Returns every published tool as the descriptor its server listed, renamed.
    ///
    /// The relay publishes these rather than {@link #tools()}: a descriptor
    /// keeps the server's own input schema, which the server side converts for
    /// itself.
    ///
    /// @return the descriptors under their published names, never null (may be empty)
    public List<McpToolDescriptor> descriptors() {
        return List.copyOf(descriptors.values());
    }

    /// Returns the server that published a name.
    ///
    /// @param toolName the published name, not null
    /// @return the owning route, or null when no server publishes the name
    public R route(String toolName) {
        return routes.get(toolName);
    }

    /// Republishes a discovered tool under its prefixed name.
    ///
    /// The prefix is a property of the catalog this runtime assembles, not of the
    /// tool the server published, so the rename happens here rather than in the
    /// schema converter, which every runtime shares.
    /// Returns the listed tool a filter entry names in its published form, or
    /// null when the entry is a server-side name or names nothing listed.
    private static String meantBeforePrefix(String entry, String prefix, Set<String> listed) {
        if (prefix.isEmpty() || listed.contains(entry) || !entry.startsWith(prefix)) {
            return null;
        }
        String stripped = entry.substring(prefix.length());
        return listed.contains(stripped) ? stripped : null;
    }

    private static String misspelled(
            Route route, String list, String entry, String meant, String outcome) {
        return "MCP server '"
                + route.name()
                + "' declares tools."
                + list
                + " '"
                + entry
                + "', which names the tool after prefix '"
                + route.spec().prefix()
                + "' is applied; tools: takes the server's own names, so write '"
                + meant
                + "'. "
                + outcome;
    }

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
