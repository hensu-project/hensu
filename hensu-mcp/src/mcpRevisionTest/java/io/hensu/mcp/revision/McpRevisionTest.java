package io.hensu.mcp.revision;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.action.SandboxPolicy;
import io.hensu.mcp.BearerCredential;
import io.hensu.mcp.CallControl;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpConnection.McpToolDescriptor;
import io.hensu.mcp.McpException;
import io.hensu.mcp.McpResultRenderer;
import io.hensu.mcp.McpServerSpec;
import io.hensu.mcp.StdioMcpConnection;
import io.hensu.mcp.StreamableHttpMcpConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/// Lists, calls and cancels against real MCP servers pinned to each protocol
/// revision Hensu has to reach.
///
/// The unit suite proves the client against fixtures written from the spec;
/// this suite proves it against servers written by somebody else, built from
/// the reference SDKs at the version that speaks each revision. Every Streamable
/// HTTP case prints the revision the connection settled on, because that – not
/// only whether a call worked – is what the suite exists to find out: a server
/// of the 2025 era that answered `initialize(2024-11-05)` with its own revision,
/// and then required the `MCP-Protocol-Version` header, would fail here first.
///
/// ### What Is Pinned
/// Each `everything` image installs one release of the server and forces the
/// SDK that decides its revision through npm `overrides`. Transitive packages
/// below the SDK float, which cannot change the revision a server speaks.
///
/// @implNote Needs Docker and network access to build the images; run through
///     `./gradlew :hensu-mcp:mcpRevisionTest`, never as part of `check`.
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class McpRevisionTest {

    /// The `everything` server at the release that speaks each revision.
    enum Everything {
        /// SDK 1.0.1 – stdio only: Streamable HTTP postdates this revision.
        R2024_11_05(
                "2024-11-05",
                Map.of("@modelcontextprotocol/server-everything", "0.6.2"),
                Map.of("@modelcontextprotocol/sdk", "1.0.1"),
                false,
                "longRunningOperation"),
        /// SDK 1.17.5, whose newest revision is 2025-06-18.
        R2025_06_18(
                "2025-06-18",
                Map.of("@modelcontextprotocol/server-everything", "2025.7.29"),
                Map.of("@modelcontextprotocol/sdk", "1.17.5"),
                true,
                "longRunningOperation"),
        /// SDK 1.25.2, whose newest revision is 2025-11-25.
        R2025_11_25(
                "2025-11-25",
                Map.of("@modelcontextprotocol/server-everything", "2026.1.26"),
                Map.of("@modelcontextprotocol/sdk", "1.25.2"),
                true,
                "trigger-long-running-operation"),
        /// SDK 2.3.1, which serves 2026-07-28 beside the 2025 era on one endpoint.
        R2026_07_28(
                "2026-07-28",
                Map.of("@modelcontextprotocol/server-everything", "2.2.1"),
                Map.of(
                        "@modelcontextprotocol/server", "2.3.1",
                        "@modelcontextprotocol/server-legacy", "2.3.1",
                        "@modelcontextprotocol/core", "2.3.1",
                        "@modelcontextprotocol/node", "2.1.1"),
                true,
                "trigger-long-running-operation");

        final String revision;
        final Map<String, String> dependencies;
        final Map<String, String> overrides;
        final boolean servesHttp;
        final String slowTool;

        Everything(
                String revision,
                Map<String, String> dependencies,
                Map<String, String> overrides,
                boolean servesHttp,
                String slowTool) {
            this.revision = revision;
            this.dependencies = dependencies;
            this.overrides = overrides;
            this.servesHttp = servesHttp;
            this.slowTool = slowTool;
        }

        String image() {
            return new ImageFromDockerfile("hensu-mcp-revision/everything:" + revision, false)
                    .withFileFromPath("Dockerfile", dockerfile("everything"))
                    .withFileFromString("package.json", packageJson())
                    .get();
        }

        private String packageJson() {
            return "{\"name\":\"revision-"
                    + revision
                    + "\",\"private\":true,\"dependencies\":"
                    + json(dependencies)
                    + ",\"overrides\":"
                    + json(overrides)
                    + "}";
        }

        private static String json(Map<String, String> entries) {
            return entries.entrySet().stream()
                    .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
                    .collect(Collectors.joining(",", "{", "}"));
        }
    }

    private static final long STARTUP_MS = 120_000L;
    private static final long REQUEST_MS = 60_000L;

    @TempDir Path workingDirectory;

    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable closeable : cleanup.reversed()) {
            closeable.close();
        }
    }

    // ------------------------------------------------------------------ cases

    @ParameterizedTest(name = "everything {0} over stdio")
    @EnumSource(Everything.class)
    void shouldListCallAndCancelOverStdio(Everything server) {
        McpConnection connection = stdio(server);

        exercise("everything " + server.revision + " over stdio", connection, server.slowTool);
    }

    @ParameterizedTest(name = "everything {0} over Streamable HTTP")
    @MethodSource("httpServers")
    void shouldListCallAndCancelOverStreamableHttp(Everything server) {
        GenericContainer<?> container =
                owned(new GenericContainer<>(server.image()))
                        .withCommand("streamableHttp")
                        .withExposedPorts(3001)
                        .waitingFor(Wait.forListeningPort());
        container.start();
        StreamableHttpMcpConnection connection = http(container, 3001);

        report("everything " + server.revision + " over Streamable HTTP", connection.revision());
        exercise(
                "everything " + server.revision + " over Streamable HTTP",
                connection,
                server.slowTool);
    }

    @Test
    void shouldListCallAndCancelAgainstASpringAiServer() {
        String image =
                new ImageFromDockerfile("hensu-mcp-revision/spring-ai:2.0.2", false)
                        .withDockerfile(dockerfile("spring-ai-server"))
                        .get();
        GenericContainer<?> container =
                owned(new GenericContainer<>(image))
                        .withExposedPorts(8080)
                        .waitingFor(
                                Wait.forLogMessage(".*Started RevisionServer.*", 1)
                                        .withStartupTimeout(Duration.ofMinutes(3)));
        container.start();
        StreamableHttpMcpConnection connection = http(container, 8080);

        report("Spring AI 2.0.2 over Streamable HTTP", connection.revision());
        assertThat(names(connection.listTools())).contains("echo", "slow");
        assertThat(render(connection.callTool("echo", Map.of("message", "hi"), CallControl.none())))
                .contains("hi");
        assertCancelEndsTheCallAndKeepsTheConnection(connection, "slow", Map.of("seconds", 30));
    }

    static Stream<Everything> httpServers() {
        return Stream.of(Everything.values()).filter(server -> server.servesHttp);
    }

    // ------------------------------------------------------------- exercising

    private void exercise(String label, McpConnection connection, String slowTool) {
        List<McpToolDescriptor> tools = connection.listTools();
        System.out.println("REVISION: " + label + " lists " + names(tools));
        assertThat(names(tools)).contains("echo", slowTool);

        assertThat(render(connection.callTool("echo", Map.of("message", "hi"), CallControl.none())))
                .contains("hi");

        assertCancelEndsTheCallAndKeepsTheConnection(
                connection, slowTool, Map.of("duration", 30, "steps", 30));
    }

    /// Cancels a 30-second call a second in, and checks the connection still answers.
    private static void assertCancelEndsTheCallAndKeepsTheConnection(
            McpConnection connection, String slowTool, Map<String, Object> slowArguments) {
        CallControl control = CallControl.create();
        long started = System.nanoTime();
        CompletableFuture<Throwable> call =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                connection.callTool(slowTool, slowArguments, control);
                                return null;
                            } catch (Throwable failure) {
                                return failure;
                            }
                        });
        sleep();
        control.cancel();

        Throwable failure = call.join();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(failure)
                .isInstanceOf(McpException.class)
                .hasMessageContaining("cancelled by the caller");
        assertThat(elapsedMs)
                .as("the cancel, not the 30-second tool, ended the call")
                .isLessThan(10_000);
        assertThat(
                        render(
                                connection.callTool(
                                        "echo", Map.of("message", "after"), CallControl.none())))
                .contains("after");
    }

    // ---------------------------------------------------------------- servers

    private McpConnection stdio(Everything server) {
        String image = server.image();
        String name = "hensu-revision-" + UUID.randomUUID();
        Map<String, String> env = new LinkedHashMap<>();
        Stream.of("HOME", "DOCKER_HOST", "DOCKER_CONFIG")
                .forEach(
                        key -> {
                            String value = System.getenv(key);
                            if (value != null) {
                                env.put(key, value);
                            }
                        });
        StdioMcpConnection connection =
                StdioMcpConnection.open(
                        new McpServerSpec.Stdio(
                                "everything",
                                "",
                                McpServerSpec.ToolFilter.ALL,
                                List.of(
                                        docker(), "run", "--rm", "-i", "--name", name, image,
                                        "stdio"),
                                env,
                                SandboxPolicy.restrictive(),
                                STARTUP_MS,
                                REQUEST_MS,
                                false,
                                false),
                        workingDirectory,
                        UnaryOperator.identity(),
                        System.getenv());
        cleanup.add(() -> new ProcessBuilder(docker(), "rm", "-f", name).start().waitFor());
        cleanup.add(connection::close);
        return connection;
    }

    /// Registers the container for teardown before it is configured or started, so a container
    /// whose start fails is still stopped.
    private GenericContainer<?> owned(GenericContainer<?> container) {
        cleanup.add(container);
        return container;
    }

    private StreamableHttpMcpConnection http(GenericContainer<?> container, int port) {
        URI endpoint =
                URI.create(
                        "http://"
                                + container.getHost()
                                + ":"
                                + container.getMappedPort(port)
                                + "/mcp");
        McpServerSpec.Http base = McpServerSpec.Http.of("revision", endpoint);
        HttpClient client = StreamableHttpMcpConnection.defaultClient();
        cleanup.add(client);
        StreamableHttpMcpConnection connection =
                StreamableHttpMcpConnection.open(
                        new McpServerSpec.Http(
                                base.name(),
                                "",
                                McpServerSpec.ToolFilter.ALL,
                                endpoint,
                                null,
                                Map.of(),
                                STARTUP_MS,
                                REQUEST_MS,
                                false,
                                false),
                        BearerCredential.NONE,
                        Set.of(base.host()),
                        client);
        cleanup.add(connection::close);
        return connection;
    }

    /// The Dockerfile of one server, whose directory is the image's build context.
    private static Path dockerfile(String server) {
        String contexts = System.getProperty("hensu.revision.contexts");
        if (contexts == null) {
            throw new IllegalStateException(
                    "hensu.revision.contexts is not set; run through"
                            + " ./gradlew :hensu-mcp:mcpRevisionTest");
        }
        return Path.of(contexts, server, "Dockerfile");
    }

    private static String docker() {
        for (String directory : System.getenv().getOrDefault("PATH", "").split(":")) {
            Path candidate = Path.of(directory, "docker");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return "docker";
    }

    // ---------------------------------------------------------------- helpers

    private static void report(String label, String revision) {
        System.out.println("REVISION: " + label + " settled on " + revision);
    }

    private static List<String> names(List<McpToolDescriptor> tools) {
        return tools.stream().map(McpToolDescriptor::name).toList();
    }

    private static String render(Map<String, Object> result) {
        return McpResultRenderer.render(result);
    }

    private static void sleep() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
