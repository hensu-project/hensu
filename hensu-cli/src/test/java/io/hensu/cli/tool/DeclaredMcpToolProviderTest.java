package io.hensu.cli.tool;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import io.hensu.cli.daemon.CredentialsStore;
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
import io.hensu.mcp.FakeHttpMcpServer;
import io.hensu.mcp.FakeMcpServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclaredMcpToolProviderTest {

    @TempDir Path workingDirectory;

    private CommandCatalog catalog;
    private RecordingLauncher launcher;
    private DeclaredMcpToolProvider provider;

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

    private DeclaredMcpToolProvider provider(SandboxLauncher backend) {
        return provider(backend, null, new ToolSourceNotices(), isolatedCredentials());
    }

    private DeclaredMcpToolProvider provider(SandboxLauncher backend, ToolSourceNotices notices) {
        return provider(backend, null, notices, isolatedCredentials());
    }

    private DeclaredMcpToolProvider provider(
            SandboxLauncher backend, ToolSourceNotices notices, CredentialsStore credentials) {
        return provider(backend, null, notices, credentials);
    }

    /// Every provider in this class is built here, with every collaborator explicit.
    ///
    /// The production constructor reads the operator's real credential store; a test
    /// must never reach it, so the only store a test provider sees is one under the
    /// test's own temporary directory.
    private DeclaredMcpToolProvider provider(
            SandboxLauncher backend,
            ToolApprovalGate gate,
            ToolSourceNotices notices,
            CredentialsStore credentials) {
        provider =
                new DeclaredMcpToolProvider(
                        catalog, backend, gate, System.getenv(), notices, credentials);
        return provider;
    }

    private CredentialsStore isolatedCredentials() {
        return new CredentialsStore(workingDirectory.resolve("credentials"));
    }

    /// Collects everything written to `java.util.logging` while a body runs.
    private static String capturingLogs(Runnable body) {
        StringBuilder captured = new StringBuilder();
        Handler sink =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        captured.append(record.getMessage()).append('\n');
                        if (record.getThrown() != null) {
                            captured.append(record.getThrown()).append('\n');
                        }
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        Logger root = Logger.getLogger("");
        Level previous = root.getLevel();
        root.addHandler(sink);
        root.setLevel(Level.ALL);
        try {
            body.run();
        } finally {
            root.removeHandler(sink);
            root.setLevel(previous);
        }
        return captured.toString();
    }

    @Nested
    class RemoteServers {

        private FakeHttpMcpServer remote;

        @BeforeEach
        void startRemote() throws IOException {
            remote = FakeHttpMcpServer.start();
        }

        @AfterEach
        void stopRemote() {
            remote.close();
        }

        private void declareRemote(String extraKeys) throws IOException {
            Files.writeString(
                    workingDirectory.resolve(McpConfig.FILE_NAME),
                    "servers:\n"
                            + "  acme:\n"
                            + "    url: \""
                            + remote.uri()
                            + "\"\n"
                            + "    unattended: true\n"
                            + extraKeys);
        }

        private CredentialsStore store(Map<String, String> entries) throws IOException {
            Path file = workingDirectory.resolve("credentials");
            CredentialsStore credentials = new CredentialsStore(file);
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                credentials.set(entry.getKey(), entry.getValue());
            }
            return credentials;
        }

        @Test
        void shouldPublishARemoteServersToolsAndCallThem() throws IOException {
            declareRemote("");

            ToolProvider open = provider(launcher, new ToolSourceNotices(), store(Map.of()));

            assertThat(open.tools()).extracting(ToolDefinition::name).containsExactly("read_file");
            ToolCallResult result = open.call("read_file", Map.of("path", "/x"), Map.of());
            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(result.output()).isEqualTo("file data over http");
        }

        @Test
        void shouldStopAServerWhoseCredentialKeyIsNotInTheStore() throws IOException {
            // The alternative is worse than an absent tool: falling through to an
            // unauthenticated call sends the request out without the credential
            // the operator meant to attach to it.
            declareRemote("    auth: {bearer: HENSU_MCP_ACME_TOKEN}\n");
            CredentialsStore credentials = store(Map.of("SOMETHING_ELSE", "x"));
            ToolSourceNotices notices = new ToolSourceNotices();

            assertThat(provider(launcher, notices, credentials).tools()).isEmpty();

            assertThat(notices.all())
                    .singleElement(as(STRING))
                    .contains("HENSU_MCP_ACME_TOKEN")
                    .contains(credentials.path().toString());
            assertThat(remote.requests())
                    .as("nothing may leave before the credential is resolved")
                    .isEmpty();
        }

        @Test
        void shouldKeepTheTokenOutOfEveryChannelAnOperatorCanRead() throws IOException {
            String secret = "sk-live-do-not-leak-1234567890";
            declareRemote("    auth: {bearer: HENSU_MCP_ACME_TOKEN}\n");
            CredentialsStore credentials = store(Map.of("HENSU_MCP_ACME_TOKEN", secret));
            ToolSourceNotices notices = new ToolSourceNotices();

            DeclaredMcpToolProvider open = provider(launcher, notices, credentials);
            String logs =
                    capturingLogs(
                            () -> {
                                open.tools();
                                open.call("read_file", Map.of("path", "/x"), Map.of());
                            });

            assertThat(logs).doesNotContain(secret);
            assertThat(notices.all()).noneSatisfy(n -> assertThat(n).contains(secret));

            var frame = open.preview("read_file", Map.of("path", "/x"));
            assertThat(frame.summary()).doesNotContain(secret);
            assertThat(frame.sandboxSummary()).doesNotContain(secret);
            assertThat(frame.argv()).noneSatisfy(a -> assertThat(a).contains(secret));

            // ...and the server did receive it, so the assertion above is about
            // where the token went rather than about it never existing.
            assertThat(remote.requests())
                    .anySatisfy(
                            request ->
                                    assertThat(request.header("authorization"))
                                            .isEqualTo("Bearer " + secret));
        }

        @Test
        void shouldDenyARedirectOffTheDeclaredHostsAndKeepTheRunGoing() throws IOException {
            declareRemote("");
            ToolSourceNotices notices = new ToolSourceNotices();
            DeclaredMcpToolProvider open = provider(launcher, notices, store(Map.of()));
            assertThat(open.tools()).hasSize(1);

            remote.redirectingTo("https://exfiltration.invalid/mcp");
            ToolCallResult refused = open.call("read_file", Map.of("path", "/x"), Map.of());

            assertThat(refused.status()).isEqualTo(ToolCallStatus.DENIED);
            assertThat(refused.error()).contains("exfiltration.invalid");

            remote.redirectingTo(null);
            assertThat(open.call("read_file", Map.of("path", "/x"), Map.of()).status())
                    .as("a refused destination costs the call, not the connection")
                    .isEqualTo(ToolCallStatus.SUCCESS);
        }

        /// A tool the transport refuses is named to the operator, not dropped silently.
        ///
        /// The connection leaves the tool out as the spec requires, but the CLI
        /// runs with its console log off, so without the notice the tool would
        /// simply be missing with nothing anywhere saying why.
        @Test
        void shouldNameATransportRejectedToolToTheOperatorAndKeepItsNeighbours()
                throws IOException {
            remote.publishingTool(
                    "measure",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("ratio", Map.of("type", "number", "x-mcp-header", "Ratio"))));
            declareRemote("");
            ToolSourceNotices notices = new ToolSourceNotices();

            ToolProvider open = provider(launcher, notices, store(Map.of()));

            assertThat(open.tools()).extracting(ToolDefinition::name).containsExactly("read_file");
            assertThat(notices.all())
                    .singleElement(as(STRING))
                    .contains("'measure'")
                    .contains("x-mcp-header")
                    .contains("ratio");
        }

        @Test
        void shouldTreatAnArgumentWithNoHeaderFormAsTheAgentsToFix() throws IOException {
            remote.publishingSchema(
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("path", Map.of("type", "string", "x-mcp-header", "Path"))));
            declareRemote("");
            ToolProvider open = provider(launcher, new ToolSourceNotices(), store(Map.of()));
            open.tools();

            ToolCallResult refused =
                    open.call("read_file", Map.of("path", List.of("/a", "/b")), Map.of());

            assertThat(refused.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(refused.error()).contains("'path'").contains("Mcp-Param-Path");
            assertThat(remote.requests())
                    .as("an argument that cannot be mirrored must not leave the machine")
                    .noneSatisfy(r -> assertThat(r.rpcMethod()).isEqualTo("tools/call"));
            assertThat(open.call("read_file", Map.of("path", "/a"), Map.of()).status())
                    .as("the refusal costs the call, not the connection")
                    .isEqualTo(ToolCallStatus.SUCCESS);
        }

        /// One declared server may not redirect a call into another's host.
        ///
        /// This is the shape the run-wide allowlist makes reachable: both hosts
        /// are legitimately declared, so an allowlist check alone passes, and
        /// the request that followed would carry the *first* server's bearer
        /// token to the second one's host.
        @Test
        void shouldRefuseARedirectIntoAnotherDeclaredServersHost() throws IOException {
            try (FakeHttpMcpServer second = FakeHttpMcpServer.start()) {
                Files.writeString(
                        workingDirectory.resolve(McpConfig.FILE_NAME),
                        "servers:\n"
                                + "  first:\n"
                                + "    url: \""
                                + remote.uri()
                                + "\"\n"
                                + "    auth: {bearer: HENSU_MCP_FIRST_TOKEN}\n"
                                + "    unattended: true\n"
                                + "  second:\n"
                                + "    url: \""
                                + second.uri()
                                + "\"\n"
                                + "    prefix: \"alt_\"\n"
                                + "    unattended: true\n");
                DeclaredMcpToolProvider open =
                        provider(
                                launcher,
                                new ToolSourceNotices(),
                                store(Map.of("HENSU_MCP_FIRST_TOKEN", "sk-live-do-not-leak")));
                assertThat(open.tools()).hasSize(2);

                remote.redirectingTo(second.uri().toString());
                int beforeTheCall = second.requests().size();

                ToolCallResult refused = open.call("read_file", Map.of("path", "/x"), Map.of());

                assertThat(refused.status()).isEqualTo(ToolCallStatus.DENIED);
                assertThat(refused.error()).contains("a different server");
                assertThat(second.requests())
                        .as("the second server must not have been reached at all")
                        .hasSize(beforeTheCall);
            }
        }

        @Test
        void shouldSayThatAnApprovedRemoteCallLeavesTheMachine() throws IOException {
            declareRemote("");
            DeclaredMcpToolProvider open =
                    provider(launcher, new ToolSourceNotices(), store(Map.of()));
            open.tools();

            var frame = open.preview("read_file", Map.of("path", "/x"));

            assertThat(frame.summary())
                    .contains(remote.uri().toString())
                    .contains("leaves the machine");
            assertThat(frame.sandboxSummary()).contains("no containment applies");
        }

        @Test
        void shouldRefuseRatherThanEscalateWhenApprovalIsRequiredInAnUnattendedRun()
                throws IOException {
            declareRemote("    approval: required\n");
            DeclaredMcpToolProvider open =
                    provider(launcher, new ToolSourceNotices(), store(Map.of()));
            open.tools();

            ToolProvider gated =
                    new ApprovalToolProvider(open, new ToolApprovalGate(new DaemonReviewHandler()));

            // An empty context is an unattended run: the mode key is absent.
            ToolCallResult refused = gated.call("read_file", Map.of("path", "/x"), Map.of());

            assertThat(refused.status()).isEqualTo(ToolCallStatus.DENIED);
        }

        @Test
        void shouldKeepTwoServersPublishingOneNameApartWithAPrefix() throws IOException {
            try (FakeHttpMcpServer second = FakeHttpMcpServer.start()) {
                Files.writeString(
                        workingDirectory.resolve(McpConfig.FILE_NAME),
                        "servers:\n"
                                + "  first:\n"
                                + "    url: \""
                                + remote.uri()
                                + "\"\n"
                                + "  second:\n"
                                + "    url: \""
                                + second.uri()
                                + "\"\n"
                                + "    prefix: \"alt_\"\n");
                ToolSourceNotices notices = new ToolSourceNotices();

                ToolProvider open = provider(launcher, notices, store(Map.of()));

                assertThat(open.tools())
                        .extracting(ToolDefinition::name)
                        .containsExactlyInAnyOrder("read_file", "alt_read_file");
                assertThat(notices.all()).isEmpty();
                assertThat(open.call("alt_read_file", Map.of("path", "/x"), Map.of()).status())
                        .isEqualTo(ToolCallStatus.SUCCESS);
            }
        }

        @Test
        void shouldStillNameTheLoserWhenNeitherServerCarriesAPrefix() throws IOException {
            try (FakeHttpMcpServer second = FakeHttpMcpServer.start()) {
                Files.writeString(
                        workingDirectory.resolve(McpConfig.FILE_NAME),
                        "servers:\n"
                                + "  first:\n"
                                + "    url: \""
                                + remote.uri()
                                + "\"\n"
                                + "  second:\n"
                                + "    url: \""
                                + second.uri()
                                + "\"\n");
                ToolSourceNotices notices = new ToolSourceNotices();

                ToolProvider open = provider(launcher, notices, store(Map.of()));

                assertThat(open.tools())
                        .extracting(ToolDefinition::name)
                        .containsExactly("read_file");
                assertThat(notices.all())
                        .singleElement(as(STRING))
                        .contains("read_file")
                        .contains("prefix:");
            }
        }
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

            DeclaredMcpToolProvider lazy = provider(launcher);
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

            DeclaredMcpToolProvider gated =
                    provider(
                            new UnavailableSandboxLauncher("test"),
                            gate,
                            new ToolSourceNotices(),
                            isolatedCredentials());

            assertThat(gated.tools()).extracting(ToolDefinition::name).containsExactly("echo");
            assertThat(gate.asked).hasSize(1);
        }

        @Test
        void shouldNotAskAnAbsentReviewerAndSkipTheServerInstead() throws IOException {
            declareFakeServer("fixture");
            ScriptedGate gate = new ScriptedGate(ApprovalOutcome.APPROVED, true);

            DeclaredMcpToolProvider gated =
                    provider(
                            new UnavailableSandboxLauncher("test"),
                            gate,
                            new ToolSourceNotices(),
                            isolatedCredentials());

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
            DeclaredMcpToolProvider running = provider(launcher);

            ToolCallResult result = running.call("echo", Map.of("message", "hello"), Map.of());

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(result.output()).isEqualTo("echoed hello");
        }

        @Test
        void shouldFailLoudlyAndShrinkTheCatalogWhenAServerDiesMidRun() throws IOException {
            // No restart policy: a crashing server that is quietly respawned is
            // worse than one whose absence the declared-versus-available diff names.
            declareFakeServer("fixture", "--exit-on", "tools/call");
            DeclaredMcpToolProvider running = provider(launcher);
            assertThat(running.tools()).hasSize(1);

            ToolCallResult result = running.call("echo", Map.of("message", "hi"), Map.of());

            assertThat(result.status()).isEqualTo(ToolCallStatus.FAILURE);
            assertThat(running.tools()).isEmpty();
        }

        @Test
        void shouldDescribeACallWithArgumentNamesButNoValues() throws IOException {
            declareFakeServer("fixture");
            DeclaredMcpToolProvider running = provider(launcher);
            running.tools();

            var preview = running.preview("echo", Map.of("message", "sk-secret-value"));

            assertThat(preview.summary()).contains("fixture").contains("echo").contains("message");
            assertThat(preview.summary()).doesNotContain("sk-secret-value");
            assertThat(preview.argv()).isEmpty();
            assertThat(preview.unattendedSafe()).isTrue();
        }

        @Test
        void shouldRefuseToDescribeAToolNoRunningServerOffers() {
            DeclaredMcpToolProvider running = provider(launcher);

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
                    .hasMessageContaining("DeclaredMcpToolProvider");
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
