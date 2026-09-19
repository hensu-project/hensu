package io.hensu.cli.tool;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import io.hensu.cli.review.ApprovalOutcome;
import io.hensu.cli.review.DaemonReviewHandler;
import io.hensu.cli.review.ToolApprovalRequest;
import io.hensu.cli.sandbox.SandboxLauncher;
import io.hensu.cli.sandbox.UnavailableSandboxLauncher;
import io.hensu.core.execution.action.CommandDefinition;
import io.hensu.core.execution.action.CommandRegistry;
import io.hensu.core.execution.action.SandboxPolicy;
import io.hensu.core.execution.action.ToolSpec;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolProvider;
import io.hensu.core.tool.ToolRouter;
import io.hensu.mcp.FakeMcpServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalMcpToolProviderTest {

    @TempDir Path workingDirectory;

    private CommandCatalog catalog;
    private RecordingLauncher launcher;
    private LocalMcpToolProvider provider;

    @BeforeEach
    void setUp() {
        catalog = new CommandCatalog();
        catalog.setWorkingDirectory(workingDirectory);
        launcher = new RecordingLauncher();
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.shutdown();
        }
    }

    /// Writes an `mcp.yaml` launching the shared fake server under a given name.
    private void declareFakeServer(String id, String... extraArgs) throws IOException {
        List<String> argv = new ArrayList<>();
        argv.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        argv.add("-cp");
        argv.add(System.getProperty("java.class.path"));
        argv.add(FakeMcpServer.class.getName());
        argv.addAll(List.of(extraArgs));

        StringBuilder yaml = new StringBuilder("servers:\n  " + id + ":\n    command: [");
        for (int i = 0; i < argv.size(); i++) {
            yaml.append(i == 0 ? "" : ", ").append('"').append(argv.get(i)).append('"');
        }
        yaml.append("]\n    unattended: true\n");
        Files.writeString(workingDirectory.resolve(McpConfig.FILE_NAME), yaml.toString());
    }

    private LocalMcpToolProvider provider(SandboxLauncher backend) {
        provider = new LocalMcpToolProvider(catalog, backend, null, System.getenv());
        return provider;
    }

    private LocalMcpToolProvider provider(SandboxLauncher backend, ToolSourceNotices notices) {
        provider = new LocalMcpToolProvider(catalog, backend, null, System.getenv(), notices);
        return provider;
    }

    @Nested
    class WhatTheOperatorIsTold {

        @Test
        void shouldReportAnUnreadableDeclarationRatherThanLeaveTheToolsQuietlyAbsent()
                throws IOException {
            // The CLI ships quarkus.log.console.level=OFF, so the warning this used to
            // raise reached nobody: the operator saw a node fail on a tool they had
            // declared, with nothing anywhere saying their mcp.yaml had not parsed.
            Files.writeString(
                    workingDirectory.resolve(McpConfig.FILE_NAME),
                    "servers:\n  fixture:\n    command: [\"/bin/echo\", \"{workdir}/server.py\"]\n");
            ToolSourceNotices notices = new ToolSourceNotices();

            assertThat(provider(launcher, notices).tools()).isEmpty();

            assertThat(notices.all())
                    .singleElement(as(STRING))
                    .contains(McpConfig.FILE_NAME)
                    .contains("{workdir}");
        }

        @Test
        void shouldReportAServerThatCouldNotStart() throws IOException {
            Files.writeString(
                    workingDirectory.resolve(McpConfig.FILE_NAME),
                    """
                            servers:
                              fixture:
                                command: ["/nonexistent/server"]
                                unattended: true
                            """);
            ToolSourceNotices notices = new ToolSourceNotices();

            assertThat(provider(launcher, notices).tools()).isEmpty();

            assertThat(notices.all())
                    .singleElement(as(STRING))
                    .contains("fixture")
                    .contains("did not start");
        }

        @Test
        void shouldSayNothingWhenEveryDeclaredServerAnswered() throws IOException {
            declareFakeServer("fixture");
            ToolSourceNotices notices = new ToolSourceNotices();

            assertThat(provider(launcher, notices).tools()).isNotEmpty();

            // A section printed on every healthy run is a section nobody reads.
            assertThat(notices.all()).isEmpty();
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void shouldLaunchNothingUntilTheCatalogIsFirstRead() throws IOException {
            declareFakeServer("fixture");

            LocalMcpToolProvider lazy = provider(launcher);
            assertThat(launcher.wrapped).isEmpty();

            assertThat(lazy.tools()).extracting(ToolDefinition::name).containsExactly("echo");
            assertThat(launcher.wrapped).hasSize(1);
        }

        @Test
        void shouldContainTheLaunchWithTheDeclaredPolicy() throws IOException {
            declareFakeServer("fixture");

            provider(launcher).tools();

            assertThat(launcher.policies).singleElement().isEqualTo(SandboxPolicy.restrictive());
        }

        @Test
        void shouldGiveEachServerItsOwnWritableHomeRatherThanTheProject() throws IOException {
            // The working directory is bound read-only unless the server declared
            // otherwise, so handing it over as the private home would grant write
            // access nobody declared.
            declareFakeServer("fixture");

            provider(launcher).tools();

            assertThat(launcher.homes).singleElement().isNotEqualTo(workingDirectory);
            assertThat(launcher.homes.getFirst()).exists().isDirectory();
        }

        @Test
        void shouldSkipAServerRatherThanStartItWithoutContainment() throws IOException {
            // A server process outlives every call, so an unavailable backend is
            // decided once, at launch – never demoted to an uncontained start.
            declareFakeServer("fixture");

            assertThat(provider(new UnavailableSandboxLauncher("test")).tools()).isEmpty();
        }

        @Test
        void shouldStartAnUncontainedServerOnlyAfterAReviewerApprovedThatLaunch()
                throws IOException {
            declareFakeServer("fixture");
            ScriptedGate gate = new ScriptedGate(ApprovalOutcome.APPROVED, false);

            LocalMcpToolProvider gated =
                    new LocalMcpToolProvider(
                            catalog, new UnavailableSandboxLauncher("test"), gate, System.getenv());
            provider = gated;

            assertThat(gated.tools()).extracting(ToolDefinition::name).containsExactly("echo");
            assertThat(gate.asked).hasSize(1);
        }

        @Test
        void shouldNotAskAnAbsentReviewerAndSkipTheServerInstead() throws IOException {
            declareFakeServer("fixture");
            ScriptedGate gate = new ScriptedGate(ApprovalOutcome.APPROVED, true);

            LocalMcpToolProvider gated =
                    new LocalMcpToolProvider(
                            catalog, new UnavailableSandboxLauncher("test"), gate, System.getenv());
            provider = gated;

            // An unattended run has nobody to approve an uncontained long-lived process,
            // so the question is not asked and the server does not start.
            assertThat(gated.tools()).isEmpty();
            assertThat(gate.asked).isEmpty();
        }

        @Test
        void shouldSurviveAMalformedDeclarationWithFewerToolsRatherThanAnException()
                throws IOException {
            Files.writeString(
                    workingDirectory.resolve(McpConfig.FILE_NAME),
                    "servers:\n  broken:\n    url: \"https://example.com\"\n");

            assertThat(provider(launcher).tools()).isEmpty();
        }

        @Test
        void shouldSurviveAServerThatDoesNotStart() throws IOException {
            Files.writeString(
                    workingDirectory.resolve(McpConfig.FILE_NAME),
                    "servers:\n  absent:\n    command: [\"/nonexistent/mcp-server\"]\n");

            assertThat(provider(launcher).tools()).isEmpty();
        }
    }

    @Nested
    class Calling {

        @Test
        void shouldRouteACallToTheOwningServerAndRenderItsResult() throws IOException {
            declareFakeServer("fixture");
            LocalMcpToolProvider running = provider(launcher);

            ToolCallResult result = running.call("echo", Map.of("message", "hello"), Map.of());

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(result.output()).isEqualTo("echoed hello");
        }

        @Test
        void shouldFailLoudlyAndShrinkTheCatalogWhenAServerDiesMidRun() throws IOException {
            // No restart policy: a crashing server that is quietly respawned is
            // worse than one whose absence the declared-versus-available diff names.
            declareFakeServer("fixture", "--exit-on", "tools/call");
            LocalMcpToolProvider running = provider(launcher);
            assertThat(running.tools()).hasSize(1);

            ToolCallResult result = running.call("echo", Map.of("message", "hi"), Map.of());

            assertThat(result.status()).isEqualTo(ToolCallStatus.FAILURE);
            assertThat(running.tools()).isEmpty();
        }

        @Test
        void shouldDescribeACallWithArgumentNamesButNoValues() throws IOException {
            declareFakeServer("fixture");
            LocalMcpToolProvider running = provider(launcher);
            running.tools();

            var preview = running.preview("echo", Map.of("message", "sk-secret-value"));

            assertThat(preview.summary()).contains("fixture").contains("echo").contains("message");
            assertThat(preview.summary()).doesNotContain("sk-secret-value");
            assertThat(preview.argv()).isEmpty();
            assertThat(preview.unattendedSafe()).isTrue();
        }

        @Test
        void shouldRefuseToDescribeAToolNoRunningServerOffers() {
            LocalMcpToolProvider running = provider(launcher);

            assertThatThrownBy(() -> running.preview("nowhere", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class DuplicateNames {

        @Test
        void shouldAbortOnTheFirstCatalogReadRatherThanAtConstruction() throws IOException {
            // A lazily-started provider is empty when the router is built, so the
            // constructor check cannot see the collision. The authoritative check
            // is the one that fires when the loop first resolves tools.
            declareFakeServer("fixture");
            catalog.setRegistry(registryPublishing());
            CommandToolProvider commands = new CommandToolProvider(catalog);
            ToolProvider mcp = provider(launcher);

            ToolRouter router = new ToolRouter(List.of(commands, mcp));

            assertThatThrownBy(router::all)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate tool 'echo'")
                    .hasMessageContaining("CommandToolProvider")
                    .hasMessageContaining("LocalMcpToolProvider");
        }

        private static CommandRegistry registryPublishing() {
            CommandRegistry registry = new CommandRegistry();
            registry.registerCommand(
                    "echo",
                    new CommandDefinition(
                            List.of("/bin/echo"),
                            null,
                            5_000L,
                            Map.of(),
                            new ToolSpec("A command of the same name", List.of()),
                            SandboxPolicy.restrictive(),
                            true,
                            false));
            return registry;
        }
    }

    /// Available backend that records what it was asked to contain and changes
    /// nothing, so a launch is observable without a real sandbox on the host.
    private static final class RecordingLauncher implements SandboxLauncher {

        private final List<List<String>> wrapped = new ArrayList<>();
        private final List<SandboxPolicy> policies = new ArrayList<>();
        private final List<Path> homes = new ArrayList<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String unavailabilityReason() {
            return "";
        }

        @Override
        public String backendName() {
            return "recording";
        }

        @Override
        public List<String> wrap(
                List<String> argv, SandboxPolicy policy, Path workingDir, Path privateHome) {
            wrapped.add(argv);
            policies.add(policy);
            homes.add(privateHome);
            return argv;
        }
    }

    /// Gate answering with one scripted outcome, recording what it was asked about.
    private static final class ScriptedGate extends ToolApprovalGate {

        private final ApprovalOutcome outcome;
        private final List<ToolApprovalRequest> asked = new ArrayList<>();

        ScriptedGate(ApprovalOutcome outcome, boolean unattended) {
            super(new DaemonReviewHandler());
            this.outcome = outcome;
            setRunMode("exec-1", unattended);
        }

        @Override
        public ApprovalOutcome ask(ToolApprovalRequest request) {
            asked.add(request);
            return outcome;
        }
    }
}
