package io.hensu.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import io.hensu.core.tool.ToolDefinition;
import io.hensu.mcp.McpConnection.McpToolDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpToolCatalogTest {

    private final List<String> refusals = new ArrayList<>();

    @Test
    void shouldPublishUnderThePrefixAndRouteBackToTheServersOwnName() {
        TestRoute github = route("github", "gh_");

        McpToolCatalog<TestRoute> catalog =
                McpToolCatalog.<TestRoute>empty()
                        .with(github, List.of(tool("search")), refusals::add);

        assertThat(catalog.tools()).extracting(ToolDefinition::name).containsExactly("gh_search");
        assertThat(catalog.route("gh_search")).isSameAs(github);
        assertThat(catalog.route("gh_search").serverSideName("gh_search")).isEqualTo("search");
        assertThat(catalog.route("search")).isNull();
        assertThat(refusals).isEmpty();
    }

    @Test
    void shouldKeepTheFirstServerToPublishANameAndNameTheLoser() {
        TestRoute first = route("first", "");
        TestRoute second = route("second", "");

        McpToolCatalog<TestRoute> catalog =
                McpToolCatalog.<TestRoute>empty()
                        .with(first, List.of(tool("search")), refusals::add)
                        .with(second, List.of(tool("search"), tool("fetch")), refusals::add);

        assertThat(catalog.route("search")).isSameAs(first);
        assertThat(catalog.route("fetch")).isSameAs(second);
        assertThat(refusals)
                .singleElement()
                .asString()
                .contains("MCP server 'second' publishes 'search'")
                .contains("Declare a prefix:");
    }

    @Test
    void shouldRefuseANameThatIsNotALegalToolNameAndKeepItsNeighbours() {
        McpToolCatalog<TestRoute> catalog =
                McpToolCatalog.<TestRoute>empty()
                        .with(
                                route("files", ""),
                                List.of(tool("read file"), tool("read_file")),
                                refusals::add);

        assertThat(catalog.tools()).extracting(ToolDefinition::name).containsExactly("read_file");
        assertThat(refusals)
                .containsExactly(
                        "MCP server 'files' publishes 'read file', which is not a legal tool"
                                + " name; it is not offered to agents");
    }

    @Test
    void shouldDropOnlyTheStoppedServersTools() {
        TestRoute dead = route("dead", "");
        TestRoute alive = route("alive", "");
        McpToolCatalog<TestRoute> catalog =
                McpToolCatalog.<TestRoute>empty()
                        .with(dead, List.of(tool("a")), refusals::add)
                        .with(alive, List.of(tool("b")), refusals::add);

        McpToolCatalog<TestRoute> remaining = catalog.without(dead.connection());

        assertThat(remaining.tools()).extracting(ToolDefinition::name).containsExactly("b");
        assertThat(remaining.route("a")).isNull();
        assertThat(catalog.route("a"))
                .as("a snapshot already handed out is unchanged")
                .isSameAs(dead);
    }

    @Test
    void shouldApplyTheToolsFilterToTheServersOwnNamesBeforeThePrefix() {
        TestRoute files =
                route(
                        "files",
                        "fs_",
                        new McpServerSpec.ToolFilter(
                                Set.of("read_file", "list_directory"), Set.of()));
        McpToolCatalog<TestRoute> catalog =
                McpToolCatalog.<TestRoute>empty()
                        .with(files, List.of(tool("read_file"), tool("write_file")), refusals::add);

        assertThat(catalog.tools())
                .extracting(ToolDefinition::name)
                .containsExactly("fs_read_file");
        assertThat(catalog.route("fs_write_file"))
                .as("a tool the operator left out is not routed")
                .isNull();
        assertThat(refusals)
                .as(
                        "leaving a tool out is the operator's choice; naming one the server lacks is not")
                .containsExactly(
                        "MCP server 'files' declares tools.allow 'list_directory', which the server"
                                + " does not list");
    }

    @Test
    void shouldDropDeniedToolsSilently() {
        TestRoute files =
                route("files", "", new McpServerSpec.ToolFilter(Set.of(), Set.of("write_file")));
        McpToolCatalog<TestRoute> catalog =
                McpToolCatalog.<TestRoute>empty()
                        .with(files, List.of(tool("read_file"), tool("write_file")), refusals::add);

        assertThat(catalog.tools()).extracting(ToolDefinition::name).containsExactly("read_file");
        assertThat(refusals).isEmpty();
    }

    /// The operator reads `fs_write_file` in the agent's catalog and denies that.
    /// Matching nothing would publish the very tool they meant to fence off.
    @Test
    void shouldStillWithholdADeniedToolWrittenWithThePrefixAndSaySo() {
        TestRoute files =
                route(
                        "files",
                        "fs_",
                        new McpServerSpec.ToolFilter(Set.of(), Set.of("fs_write_file")));
        McpToolCatalog<TestRoute> catalog =
                McpToolCatalog.<TestRoute>empty()
                        .with(files, List.of(tool("read_file"), tool("write_file")), refusals::add);

        assertThat(catalog.tools())
                .extracting(ToolDefinition::name)
                .containsExactly("fs_read_file");
        assertThat(refusals)
                .singleElement(STRING)
                .contains("tools.deny 'fs_write_file'", "write 'write_file'", "withheld");
    }

    @Test
    void shouldNotWidenAnAllowWrittenWithThePrefixButSaySo() {
        TestRoute files =
                route(
                        "files",
                        "fs_",
                        new McpServerSpec.ToolFilter(Set.of("fs_read_file"), Set.of()));
        McpToolCatalog<TestRoute> catalog =
                McpToolCatalog.<TestRoute>empty()
                        .with(files, List.of(tool("read_file"), tool("write_file")), refusals::add);

        assertThat(catalog.tools()).isEmpty();
        assertThat(refusals)
                .singleElement(STRING)
                .contains("tools.allow 'fs_read_file'", "write 'read_file'", "not offered");
    }

    /// A relay publishes descriptors, so they have to carry the published name
    /// and the server's own schema, and leave with their server.
    @Test
    void shouldKeepDescriptorsUnderThePublishedNameBesideTheDefinitions() {
        Map<String, Object> schema =
                Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")));
        TestRoute github = route("github", "gh_");
        McpToolCatalog<TestRoute> catalog =
                McpToolCatalog.<TestRoute>empty()
                        .with(
                                github,
                                List.of(new McpToolDescriptor("search", "Search code", schema)),
                                refusals::add);

        assertThat(catalog.descriptors())
                .containsExactly(new McpToolDescriptor("gh_search", "Search code", schema));
        assertThat(catalog.without(github.connection()).descriptors()).isEmpty();
    }

    private static McpToolDescriptor tool(String name) {
        return new McpToolDescriptor(name, "", Map.of("type", "object", "properties", Map.of()));
    }

    private static TestRoute route(String name, String prefix) {
        return route(name, prefix, McpServerSpec.ToolFilter.ALL);
    }

    private static TestRoute route(String name, String prefix, McpServerSpec.ToolFilter filter) {
        McpServerSpec.Stdio base = McpServerSpec.Stdio.of(name, List.of("true"));
        McpServerSpec.Stdio spec =
                new McpServerSpec.Stdio(
                        name,
                        prefix,
                        filter,
                        base.command(),
                        base.env(),
                        base.sandbox(),
                        base.startupTimeoutMs(),
                        base.requestTimeoutMs(),
                        base.unattended(),
                        base.approvalRequired());
        return new TestRoute(spec, new UnusedConnection(name));
    }

    /// The least a runtime's route has to carry.
    private record TestRoute(McpServerSpec spec, McpConnection connection)
            implements McpToolCatalog.Route {}

    /// A connection the catalog routes to but never calls.
    private record UnusedConnection(String endpoint) implements McpConnection {

        @Override
        public List<McpToolDescriptor> listTools() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> callTool(
                String toolName, Map<String, Object> arguments, CallControl control) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getEndpoint() {
            return endpoint;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void close() {}
    }
}
