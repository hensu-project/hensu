package io.hensu.cli.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.tool.ToolDefinition;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpConnection.McpToolDescriptor;
import io.hensu.mcp.McpServerSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolCatalogTest {

    private final List<String> refusals = new ArrayList<>();

    @Test
    void shouldPublishUnderThePrefixAndRouteBackToTheServersOwnName() {
        McpRoute github = route("github", "gh_");

        McpToolCatalog catalog =
                McpToolCatalog.EMPTY.with(github, List.of(tool("search")), refusals::add);

        assertThat(catalog.tools()).extracting(ToolDefinition::name).containsExactly("gh_search");
        assertThat(catalog.route("gh_search")).isSameAs(github);
        assertThat(catalog.route("gh_search").serverSideName("gh_search")).isEqualTo("search");
        assertThat(catalog.route("search")).isNull();
        assertThat(refusals).isEmpty();
    }

    @Test
    void shouldKeepTheFirstServerToPublishANameAndNameTheLoser() {
        McpRoute first = route("first", "");
        McpRoute second = route("second", "");

        McpToolCatalog catalog =
                McpToolCatalog.EMPTY
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
        McpToolCatalog catalog =
                McpToolCatalog.EMPTY.with(
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
        McpRoute dead = route("dead", "");
        McpRoute alive = route("alive", "");
        McpToolCatalog catalog =
                McpToolCatalog.EMPTY
                        .with(dead, List.of(tool("a")), refusals::add)
                        .with(alive, List.of(tool("b")), refusals::add);

        McpToolCatalog remaining = catalog.without(dead.connection());

        assertThat(remaining.tools()).extracting(ToolDefinition::name).containsExactly("b");
        assertThat(remaining.route("a")).isNull();
        assertThat(catalog.route("a"))
                .as("a snapshot already handed out is unchanged")
                .isSameAs(dead);
    }

    private static McpToolDescriptor tool(String name) {
        return new McpToolDescriptor(name, "", Map.of("type", "object", "properties", Map.of()));
    }

    private static McpRoute route(String name, String prefix) {
        McpServerSpec.Stdio base = McpServerSpec.Stdio.of(name, List.of("true"));
        McpServerSpec.Stdio spec =
                new McpServerSpec.Stdio(
                        name,
                        prefix,
                        base.command(),
                        base.env(),
                        base.sandbox(),
                        base.startupTimeoutMs(),
                        base.requestTimeoutMs(),
                        base.unattended(),
                        base.approvalRequired());
        return new McpRoute(spec, new UnusedConnection(name));
    }

    /// A connection the catalog routes to but never calls.
    private record UnusedConnection(String endpoint) implements McpConnection {

        @Override
        public List<McpToolDescriptor> listTools() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
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
