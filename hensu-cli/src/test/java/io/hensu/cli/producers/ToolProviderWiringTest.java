package io.hensu.cli.producers;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.cli.tool.ApprovalToolProvider;
import io.hensu.cli.tool.CommandCatalog;
import io.hensu.cli.tool.CommandToolProvider;
import io.hensu.cli.tool.LocalMcpToolProvider;
import io.hensu.cli.tool.ToolApprovalGate;
import io.hensu.cli.tool.WorkspaceFileToolProvider;
import io.hensu.core.tool.BuiltInToolProvider;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolProvider;
import io.hensu.core.tool.ToolRouter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The wiring that makes every other piece of the tool surface reachable.
///
/// Until a producer collects the discovered providers into a `ToolRouter` and hands it to
/// the factory, an agent declaring `read_file` gets `declared=[read_file], available=[]`
/// and none of the providers can be exercised from a terminal at all. This is that
/// contract, pinned: the CDI container finds the three CLI sources, the producer wraps
/// each in the approval policy, and the router still resolves the built-ins by name.
@QuarkusTest
class ToolProviderWiringTest {

    @Inject Instance<ToolProvider> discovered;

    @Inject ToolApprovalGate approvalGate;

    @Inject CommandCatalog catalog;

    @Test
    void shouldDiscoverEveryToolSourceTheCliShips() {
        List<String> kinds =
                discovered.stream().map(provider -> unproxy(provider).getName()).toList();

        assertThat(kinds)
                .contains(
                        WorkspaceFileToolProvider.class.getName(),
                        CommandToolProvider.class.getName(),
                        LocalMcpToolProvider.class.getName());
    }

    @Test
    void shouldResolveABuiltInByNameOnceTheProvidersAreGated() {
        ToolRouter router =
                new ToolRouter(
                        discovered.stream()
                                .map(
                                        provider ->
                                                (ToolProvider)
                                                        new ApprovalToolProvider(
                                                                provider, approvalGate))
                                .toList());

        // The regression this catches is the one the manual plan calls W1: a router that
        // resolves nothing means an agent's declared tools never meet a provider.
        assertThat(router.all()).extracting(ToolDefinition::name).contains("read_file", "grep");
        assertThat(router.get("read_file")).isPresent();
    }

    @Test
    void shouldKeepTheBuiltInsInTheirOwnPrecedenceBandThroughTheDecorator() {
        ToolProvider fileTools =
                discovered.stream()
                        .filter(provider -> unproxy(provider) == WorkspaceFileToolProvider.class)
                        .findFirst()
                        .orElseThrow();

        // Wrapping must not move the built-ins into the configured band: a deployment
        // whose filesystem MCP server also publishes read_file would then fail to start
        // instead of taking the name with a warning.
        assertThat(ToolRouter.unwrap(new ApprovalToolProvider(fileTools, approvalGate)))
                .isInstanceOf(BuiltInToolProvider.class);
    }

    @Test
    void shouldRootTheFileToolsAtTheRunsWorkingDirectory() {
        WorkspaceFileToolProvider fileTools =
                (WorkspaceFileToolProvider)
                        discovered.stream()
                                .filter(
                                        provider ->
                                                unproxy(provider)
                                                        == WorkspaceFileToolProvider.class)
                                .findFirst()
                                .orElseThrow();

        // The root is chosen by `hensu run -d <dir>`, long after CDI wired the bean. A
        // provider pinned to the JVM's start directory would confine an agent to whatever
        // directory the daemon happened to be launched from.
        assertThat(fileTools.root()).isEqualTo(catalog.workingDirectory());
    }

    private static Class<?> unproxy(ToolProvider provider) {
        Class<?> type = provider.getClass();
        return type.getSimpleName().contains("ClientProxy") ? type.getSuperclass() : type;
    }
}
