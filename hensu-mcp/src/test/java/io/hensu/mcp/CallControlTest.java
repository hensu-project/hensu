package io.hensu.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.execution.action.SandboxPolicy;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/// Exercises {@link CallControl} against the real transports: a cancel has to
/// reach the server the way its era says, end the caller's wait at once, and
/// never send a call cancelled before it left; caller `_meta` has to arrive
/// beside the era's own keys, never instead of them.
@Timeout(30)
class CallControlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// Long enough that only a cancel can end a held call within a test's patience.
    private static final long CALL_BUDGET_MS = 20_000L;

    @Nested
    class OverStreamableHttp {

        private FakeHttpMcpServer server;
        private HttpClient client;
        private StreamableHttpMcpConnection connection;

        @BeforeEach
        void setUp() throws IOException {
            server = FakeHttpMcpServer.start();
            client = StreamableHttpMcpConnection.defaultClient();
        }

        @AfterEach
        void tearDown() {
            if (connection != null) {
                connection.close();
            }
            server.close();
        }

        private StreamableHttpMcpConnection open() {
            McpServerSpec.Http base = McpServerSpec.Http.of("acme", server.uri());
            connection =
                    StreamableHttpMcpConnection.open(
                            new McpServerSpec.Http(
                                    base.name(),
                                    base.prefix(),
                                    base.tools(),
                                    base.url(),
                                    null,
                                    Map.of(),
                                    base.startupTimeoutMs(),
                                    CALL_BUDGET_MS,
                                    false,
                                    false),
                            BearerCredential.NONE,
                            Set.of(server.host()),
                            client);
            return connection;
        }

        private CompletableFuture<Throwable> callInBackground(
                StreamableHttpMcpConnection open, CallControl control) {
            return CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            open.callTool("read_file", Map.of("path", "/x"), control);
                            return null;
                        } catch (Throwable failure) {
                            return failure;
                        }
                    });
        }

        @Test
        void shouldCloseTheResponseStreamInTheModernEra() throws Exception {
            server.holdingCallsOpen();
            StreamableHttpMcpConnection open = open();
            CallControl control = CallControl.create();

            CompletableFuture<Throwable> call = callInBackground(open, control);
            assertThat(server.awaitCall(5, TimeUnit.SECONDS)).isTrue();
            control.cancel();

            assertThat(call.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("cancelled by the caller")
                    .extracting(failure -> ((McpException) failure).delivery())
                    .isEqualTo(McpException.Delivery.MAYBE_DELIVERED);
            assertThat(server.awaitHeldCallClosed(5, TimeUnit.SECONDS))
                    .as("closing the stream is the cancellation in this era")
                    .isTrue();
            assertThat(server.requests())
                    .extracting(FakeHttpMcpServer.Recorded::rpcMethod)
                    .doesNotContain("notifications/cancelled");
        }

        @Test
        void shouldAnnounceTheCancellationInTheLegacyEra() throws Exception {
            server.legacy().holdingCallsOpen();
            StreamableHttpMcpConnection open = open();
            CallControl control = CallControl.create();

            CompletableFuture<Throwable> call = callInBackground(open, control);
            assertThat(server.awaitCall(5, TimeUnit.SECONDS)).isTrue();
            control.cancel();

            assertThat(call.get(5, TimeUnit.SECONDS))
                    .hasMessageContaining("cancelled by the caller");
            assertThat(server.awaitCancellation(5, TimeUnit.SECONDS)).isTrue();
            String callId = idOf(only(server, "tools/call"));
            JsonNode params = MAPPER.readTree(only(server, "notifications/cancelled").body());
            assertThat(params.path("params").path("requestId").asText()).isEqualTo(callId);
            assertThat(params.path("params").path("reason").asText())
                    .isEqualTo("the caller cancelled the request");
        }

        @Test
        void shouldNotSendACallCancelledBeforeItLeft() {
            StreamableHttpMcpConnection open = open();
            CallControl control = CallControl.create();
            control.cancel();

            assertThatThrownBy(() -> open.callTool("read_file", Map.of("path", "/x"), control))
                    .isInstanceOf(McpException.class)
                    .extracting(failure -> ((McpException) failure).delivery())
                    .isEqualTo(McpException.Delivery.NOT_SENT);
            assertThat(server.requests())
                    .extracting(FakeHttpMcpServer.Recorded::rpcMethod)
                    .doesNotContain("tools/call");
        }

        @Test
        void shouldMergeCallerMetaBesideTheModernIdentityKeys() throws Exception {
            open().callTool(
                            "read_file",
                            Map.of("path", "/x"),
                            CallControl.withMeta(Map.of("io.hensu/callKey", "exec-1/n#1")));

            JsonNode meta =
                    MAPPER.readTree(only(server, "tools/call").body()).path("params").path("_meta");
            assertThat(meta.path("io.hensu/callKey").asText()).isEqualTo("exec-1/n#1");
            assertThat(meta.path(McpProtocol.META_PROTOCOL_VERSION).asText())
                    .isEqualTo(McpProtocol.MODERN_REVISION);
            assertThat(meta.has(McpProtocol.META_CLIENT_INFO)).isTrue();
        }

        @Test
        void shouldCarryCallerMetaAloneInTheLegacyEra() throws Exception {
            server.legacy();
            open().callTool(
                            "read_file",
                            Map.of("path", "/x"),
                            CallControl.withMeta(Map.of("io.hensu/callKey", "exec-1/n#1")));

            JsonNode meta =
                    MAPPER.readTree(only(server, "tools/call").body()).path("params").path("_meta");
            assertThat(meta.properties())
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.getKey()).isEqualTo("io.hensu/callKey"));
        }

        private static FakeHttpMcpServer.Recorded only(FakeHttpMcpServer server, String method) {
            List<FakeHttpMcpServer.Recorded> matching =
                    server.requests().stream()
                            .filter(request -> method.equals(request.rpcMethod()))
                            .toList();
            assertThat(matching).hasSize(1);
            return matching.getFirst();
        }

        private static String idOf(FakeHttpMcpServer.Recorded request) throws IOException {
            return MAPPER.readTree(request.body()).path("id").asText();
        }
    }

    @Nested
    class OverStdio {

        @TempDir Path workingDirectory;

        private StdioMcpConnection connection;

        @AfterEach
        void tearDown() {
            if (connection != null) {
                connection.close();
            }
        }

        private StdioMcpConnection open(String... extraArgs) {
            return open(CALL_BUDGET_MS, extraArgs);
        }

        private StdioMcpConnection open(long requestTimeoutMs, String... extraArgs) {
            List<String> argv =
                    new java.util.ArrayList<>(
                            List.of(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    FakeMcpServer.class.getName()));
            argv.addAll(List.of(extraArgs));
            connection =
                    StdioMcpConnection.open(
                            new McpServerSpec.Stdio(
                                    "fixture",
                                    "",
                                    McpServerSpec.ToolFilter.ALL,
                                    argv,
                                    Map.of(),
                                    SandboxPolicy.restrictive(),
                                    30_000,
                                    requestTimeoutMs,
                                    false,
                                    false),
                            workingDirectory,
                            UnaryOperator.identity(),
                            System.getenv());
            return connection;
        }

        @Test
        void shouldStopWaitingAndTellTheServerWhichRequestToDrop() throws Exception {
            Path receipt = workingDirectory.resolve("swallowed");
            StdioMcpConnection open =
                    open("--swallow-tool", "echo", "--swallow-receipt", receipt.toString());
            CallControl control = CallControl.create();

            CompletableFuture<Throwable> call =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    open.callTool("echo", Map.of("message", "hi"), control);
                                    return null;
                                } catch (Throwable failure) {
                                    return failure;
                                }
                            });
            // A cancel before the write would, correctly, send nothing at all, so wait
            // for the fixture to say it read the call. The class @Timeout bounds the poll.
            while (!Files.exists(receipt)) {
                //noinspection BusyWait
                Thread.sleep(10);
            }
            control.cancel();

            assertThat(call.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("cancelled by the caller")
                    .extracting(failure -> ((McpException) failure).delivery())
                    .isEqualTo(McpException.Delivery.MAYBE_DELIVERED);
            assertThat(
                            McpResultRenderer.render(
                                    open.callTool("cancelled", Map.of(), CallControl.none())))
                    .as("the cancelled request is the one after initialize, id 2")
                    .isEqualTo("cancelled 2");
            assertThat(open.isConnected()).isTrue();
        }

        @Test
        void shouldTellTheServerWhichRequestToDropWhenTheBudgetExpires() {
            StdioMcpConnection open = open(500L, "--swallow-tool", "echo");

            assertThatThrownBy(
                            () ->
                                    open.callTool(
                                            "echo", Map.of("message", "hi"), CallControl.none()))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("timed out after 500ms");
            assertThat(
                            McpResultRenderer.render(
                                    open.callTool("cancelled", Map.of(), CallControl.none())))
                    .as("a server left working on an abandoned call is told to drop it")
                    .isEqualTo("cancelled 2");
            assertThat(open.isConnected()).isTrue();
        }
    }

    @Test
    void shouldRefuseCallerMetaInTheProtocolsOwnNamespace() {
        assertThatThrownBy(
                        () ->
                                CallControl.withMeta(
                                        Map.of(McpProtocol.META_PROTOCOL_VERSION, "1999-01-01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(McpProtocol.META_PROTOCOL_VERSION);
    }

    @Test
    void shouldRunTheBoundActionExactlyOnceHoweverCancelRacesTheBind() throws Exception {
        for (int round = 0; round < 200; round++) {
            CallControl control = CallControl.create();
            java.util.concurrent.atomic.AtomicInteger runs =
                    new java.util.concurrent.atomic.AtomicInteger();
            Runnable action = runs::incrementAndGet;
            Thread canceller = Thread.ofVirtual().start(control::cancel);
            control.bind(action);
            canceller.join();
            control.cancel();

            assertThat(runs.get()).isEqualTo(1);
        }
    }

    @Test
    void shouldIgnoreACancelOnTheSharedInertControl() {
        CallControl.none().cancel();

        assertThat(CallControl.none().isCancelled()).isFalse();
    }
}
