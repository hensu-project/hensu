package io.hensu.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ToolRouterTest {

    private static final ToolDefinition SEARCH =
            ToolDefinition.simple("search", "Search for information");
    private static final ToolDefinition BUILD = ToolDefinition.simple("build", "Run the build");

    @Nested
    class DuplicateNames {

        @Test
        void shouldRejectAtConstructionNamingBothProviders() {
            StubToolProvider first = StubToolProvider.alwaysSucceeding("a", SEARCH);
            OtherStubProvider second = new OtherStubProvider(SEARCH);

            assertThatThrownBy(() -> new ToolRouter(List.of(first, second)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("search")
                    .hasMessageContaining("StubToolProvider")
                    .hasMessageContaining("OtherStubProvider");
        }

        @Test
        void shouldRejectWhenCollisionOnlyAppearsAfterConstruction() {
            // A dynamic catalog is empty at construction, so the constructor check
            // cannot see the collision – all() must catch it before any tool runs.
            LateCatalogProvider first = new LateCatalogProvider();
            LateCatalogProvider second = new LateCatalogProvider();
            ToolRouter router = new ToolRouter(List.of(first, second));

            assertThat(router.all()).isEmpty();

            first.publish(SEARCH);
            second.publish(SEARCH);

            assertThatThrownBy(router::all)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate tool 'search'");
        }

        @Test
        void shouldReportCatalogErrorOnCallWhenTwoProvidersClaimTheName() {
            LateCatalogProvider first = new LateCatalogProvider();
            LateCatalogProvider second = new LateCatalogProvider();
            ToolRouter router = new ToolRouter(List.of(first, second));

            first.publish(SEARCH);
            second.publish(SEARCH);

            ToolCallResult result = router.call("search", Map.of(), Map.of());

            assertThat(result.status()).isEqualTo(ToolCallStatus.CATALOG_ERROR);
            assertThat(result.error()).contains("Duplicate tool 'search'");
        }
    }

    @Nested
    class Routing {

        @Test
        void shouldRouteToTheOwningProvider() {
            StubToolProvider searchProvider = StubToolProvider.alwaysSucceeding("hits", SEARCH);
            StubToolProvider buildProvider = StubToolProvider.alwaysSucceeding("built", BUILD);
            ToolRouter router = new ToolRouter(List.of(searchProvider, buildProvider));

            ToolCallResult result = router.call("build", Map.of("clean", true), Map.of());

            assertThat(result.success()).isTrue();
            assertThat(result.output()).isEqualTo("built");
            assertThat(searchProvider.invocations()).isEmpty();
            assertThat(buildProvider.invocations())
                    .singleElement()
                    .satisfies(
                            invocation -> {
                                assertThat(invocation.toolName()).isEqualTo("build");
                                assertThat(invocation.arguments()).containsEntry("clean", true);
                            });
        }

        @Test
        void shouldExposeUnionOfProviderCatalogs() {
            ToolRouter router =
                    new ToolRouter(
                            List.of(
                                    StubToolProvider.alwaysSucceeding("hits", SEARCH),
                                    StubToolProvider.alwaysSucceeding("built", BUILD)));

            assertThat(router.all())
                    .extracting(ToolDefinition::name)
                    .containsExactly("search", "build");
            assertThat(router.get("search")).contains(SEARCH);
            assertThat(router.get("missing")).isEmpty();
            assertThat(router.contains("build")).isTrue();
            assertThat(router.size()).isEqualTo(2);
        }

        @Test
        void shouldReportUnknownToolWithoutLeakingTheCatalog() {
            // The router sees every tool in the runtime, including those outside the
            // agent's declared allowlist. Enumerating them here would hand the model
            // a catalog it was never granted, so only the loop names what is permitted.
            ToolRouter router =
                    new ToolRouter(List.of(StubToolProvider.alwaysSucceeding("hits", SEARCH)));

            ToolCallResult result = router.call("deploy", Map.of(), Map.of());

            assertThat(result.status()).isEqualTo(ToolCallStatus.UNKNOWN_TOOL);
            assertThat(result.error()).contains("Unknown tool 'deploy'").doesNotContain("search");
        }

        @Test
        void shouldConvertProviderExceptionIntoFailureResult() {
            StubToolProvider throwing =
                    new StubToolProvider(
                            List.of(SEARCH),
                            (_, _) -> {
                                throw new IllegalArgumentException("boom");
                            });
            ToolRouter router = new ToolRouter(List.of(throwing));

            ToolCallResult result = router.call("search", Map.of(), Map.of());

            assertThat(result.status()).isEqualTo(ToolCallStatus.FAILURE);
            assertThat(result.error()).contains("StubToolProvider").contains("boom");
        }

        @Test
        void shouldIsolateAProviderThatThrowsWhileReportingItsCatalog() {
            // One MCP server being down must cost its own tools, nothing else:
            // propagating here would abort the whole execution rather than one node.
            ToolRouter router =
                    new ToolRouter(
                            List.of(
                                    new BrokenCatalogProvider(),
                                    StubToolProvider.alwaysSucceeding("built", BUILD)));

            assertThat(router.all()).extracting(ToolDefinition::name).containsExactly("build");
            assertThat(router.get("build")).contains(BUILD);

            ToolCallResult result = router.call("build", Map.of(), Map.of());

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(result.output()).isEqualTo("built");
        }
    }

    /// Second provider type, so duplicate messages can name two distinct classes.
    private static final class OtherStubProvider implements ToolProvider {

        private final List<ToolDefinition> tools;

        OtherStubProvider(ToolDefinition... tools) {
            this.tools = List.of(tools);
        }

        @Override
        public List<ToolDefinition> tools() {
            return tools;
        }

        @Override
        public boolean provides(String toolName) {
            return tools.stream().anyMatch(t -> t.name().equals(toolName));
        }

        @Override
        public ToolCallResult call(
                String toolName, Map<String, Object> arguments, Map<String, Object> context) {
            return ToolCallResult.success(toolName, "other");
        }
    }

    /// Provider whose source is unreachable, so every catalog read throws.
    private static final class BrokenCatalogProvider implements ToolProvider {

        @Override
        public List<ToolDefinition> tools() {
            throw new IllegalStateException("connection refused");
        }

        @Override
        public ToolCallResult call(
                String toolName, Map<String, Object> arguments, Map<String, Object> context) {
            throw new IllegalStateException("connection refused");
        }
    }

    /// Provider whose catalog materializes after construction, like a tenant-scoped
    /// or lazily-started source.
    private static final class LateCatalogProvider implements ToolProvider {

        private List<ToolDefinition> tools = List.of();

        void publish(ToolDefinition... published) {
            this.tools = List.of(published);
        }

        @Override
        public List<ToolDefinition> tools() {
            return tools;
        }

        @Override
        public boolean provides(String toolName) {
            return tools.stream().anyMatch(t -> t.name().equals(toolName));
        }

        @Override
        public ToolCallResult call(
                String toolName, Map<String, Object> arguments, Map<String, Object> context) {
            return ToolCallResult.success(toolName, "late");
        }
    }
}
