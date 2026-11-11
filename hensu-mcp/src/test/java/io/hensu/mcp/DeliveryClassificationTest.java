package io.hensu.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.action.SandboxPolicy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Checks that a failure says whether the server can have acted on the request.
///
/// A caller that must run each call at most once retries only a `NOT_SENT`
/// failure, so the costly error is a request that left being reported as one
/// that did not. Each case pins one side of that line on a real transport.
@Timeout(60)
class DeliveryClassificationTest {

    private static McpException.Delivery deliveryOf(Throwable failure) {
        assertThat(failure).isInstanceOf(McpException.class);
        return ((McpException) failure).delivery();
    }

    @Nested
    class OverStreamableHttp {

        private final HttpClient client = StreamableHttpMcpConnection.defaultClient();
        private FakeHttpMcpServer server;
        private StreamableHttpMcpConnection connection;

        @AfterEach
        void tearDown() {
            if (connection != null) {
                connection.close();
            }
            if (server != null) {
                server.close();
            }
        }

        private StreamableHttpMcpConnection open(URI endpoint) {
            connection =
                    StreamableHttpMcpConnection.open(
                            McpServerSpec.Http.of("acme", endpoint),
                            BearerCredential.NONE,
                            Set.of("127.0.0.1"),
                            client);
            return connection;
        }

        @Test
        void shouldClassifyARefusedConnectionAsNotSent() throws IOException {
            int closedPort;
            try (ServerSocket probe = new ServerSocket()) {
                probe.bind(new InetSocketAddress("127.0.0.1", 0));
                closedPort = probe.getLocalPort();
            }
            URI nobodyListens = URI.create("http://127.0.0.1:" + closedPort + "/mcp");

            assertThatThrownBy(() -> open(nobodyListens))
                    .satisfies(
                            failure ->
                                    assertThat(deliveryOf(failure))
                                            .isEqualTo(McpException.Delivery.NOT_SENT));
        }

        @Test
        void shouldClassifyASocketDroppedAfterTheRequestAsMaybeDelivered() throws IOException {
            server = FakeHttpMcpServer.start().droppingCalls();
            StreamableHttpMcpConnection open = open(server.uri());

            assertThatThrownBy(
                            () ->
                                    open.callTool(
                                            "read_file", Map.of("path", "/x"), CallControl.none()))
                    .satisfies(
                            failure ->
                                    assertThat(deliveryOf(failure))
                                            .isEqualTo(McpException.Delivery.MAYBE_DELIVERED));
        }

        @Test
        void shouldClassifyAnErrorStatusAsMaybeDelivered() throws IOException {
            server =
                    FakeHttpMcpServer.start().failingCallsWith(502, "upstream reset", "text/plain");
            StreamableHttpMcpConnection open = open(server.uri());

            assertThatThrownBy(
                            () ->
                                    open.callTool(
                                            "read_file", Map.of("path", "/x"), CallControl.none()))
                    .satisfies(
                            failure ->
                                    assertThat(deliveryOf(failure))
                                            .as(
                                                    "a gateway error says nothing about the tool behind it")
                                            .isEqualTo(McpException.Delivery.MAYBE_DELIVERED));
        }

        /// A post-then-redirect may follow a call the server already ran, so the
        /// call is neither repeated nor reported as unsent.
        @Test
        void shouldNotRepeatACallAfterARedirectThatMayFollowItsExecution() throws IOException {
            server = FakeHttpMcpServer.start();
            StreamableHttpMcpConnection open = open(server.uri());
            server.redirectingNextCall(server.uri() + "/result", 303);

            assertThatThrownBy(
                            () ->
                                    open.callTool(
                                            "read_file", Map.of("path", "/x"), CallControl.none()))
                    .hasMessageContaining("HTTP 303")
                    .satisfies(
                            failure ->
                                    assertThat(deliveryOf(failure))
                                            .isEqualTo(McpException.Delivery.MAYBE_DELIVERED));
            assertThat(server.requests())
                    .filteredOn(request -> "tools/call".equals(request.rpcMethod()))
                    .hasSize(1);
        }

        @Test
        void shouldRepeatACallOnlyWhereTheRedirectSaysItWasNotHandled() throws IOException {
            server = FakeHttpMcpServer.start();
            StreamableHttpMcpConnection open = open(server.uri());
            server.redirectingNextCall(server.uri() + "/moved", 307);

            assertThat(
                            McpResultRenderer.render(
                                    open.callTool(
                                            "read_file", Map.of("path", "/x"), CallControl.none())))
                    .isEqualTo("file data over http");
            assertThat(server.requests())
                    .filteredOn(request -> "tools/call".equals(request.rpcMethod()))
                    .extracting(FakeHttpMcpServer.Recorded::path)
                    .containsExactly("/mcp", "/mcp/moved");
        }

        /// A relay forwards the server's own error object, so it has to arrive
        /// whole – under a success status or an error one alike.
        @ParameterizedTest
        @ValueSource(ints = {200, 400})
        void shouldKeepTheErrorObjectTheServerAnsweredWith(int status) throws IOException {
            server =
                    FakeHttpMcpServer.start()
                            .failingCallsWith(
                                    status,
                                    "{\"jsonrpc\":\"2.0\",\"id\":\"9\",\"error\":{\"code\":-32602,"
                                            + "\"message\":\"Unknown tool\",\"data\":{\"tool\":\"x\"}}}",
                                    "application/json");
            StreamableHttpMcpConnection open = open(server.uri());

            assertThatThrownBy(
                            () ->
                                    open.callTool(
                                            "read_file", Map.of("path", "/x"), CallControl.none()))
                    .isInstanceOf(McpErrorResponseException.class)
                    .as("an agent reads the same code whatever the status")
                    .hasMessageContaining("JSON-RPC error -32602")
                    .hasMessageContaining("Unknown tool")
                    .satisfies(
                            failure -> {
                                McpErrorResponseException answered =
                                        (McpErrorResponseException) failure;
                                assertThat(answered.code()).isEqualTo(-32602);
                                assertThat(answered.error())
                                        .containsEntry("message", "Unknown tool")
                                        .containsEntry("data", Map.of("tool", "x"));
                                assertThat(answered.delivery())
                                        .isEqualTo(McpException.Delivery.MAYBE_DELIVERED);
                            });
        }

        @Test
        void shouldClassifyACallOnAClosedConnectionAsNotSent() throws IOException {
            server = FakeHttpMcpServer.start();
            StreamableHttpMcpConnection open = open(server.uri());
            open.close();

            assertThatThrownBy(
                            () ->
                                    open.callTool(
                                            "read_file", Map.of("path", "/x"), CallControl.none()))
                    .satisfies(
                            failure ->
                                    assertThat(deliveryOf(failure))
                                            .isEqualTo(McpException.Delivery.NOT_SENT));
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
            List<String> argv = new ArrayList<>();
            argv.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            argv.add("-cp");
            argv.add(System.getProperty("java.class.path"));
            argv.add(FakeMcpServer.class.getName());
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
                                    10_000,
                                    false,
                                    false),
                            workingDirectory,
                            UnaryOperator.identity(),
                            System.getenv());
            return connection;
        }

        @Test
        void shouldKeepTheErrorObjectAStdioServerAnsweredWith() {
            StdioMcpConnection open = open();

            assertThatThrownBy(() -> open.callTool("fail", Map.of(), CallControl.none()))
                    .isInstanceOf(McpErrorResponseException.class)
                    .satisfies(
                            failure -> {
                                McpErrorResponseException answered =
                                        (McpErrorResponseException) failure;
                                assertThat(answered.code()).isEqualTo(-32602);
                                assertThat(answered.error())
                                        .containsEntry("data", Map.of("tool", "fail"));
                            });
        }

        @Test
        void shouldClassifyACallOnAClosedConnectionAsNotSent() {
            StdioMcpConnection open = open();
            open.close();

            assertThatThrownBy(
                            () ->
                                    open.callTool(
                                            "echo", Map.of("message", "hi"), CallControl.none()))
                    .satisfies(
                            failure ->
                                    assertThat(deliveryOf(failure))
                                            .isEqualTo(McpException.Delivery.NOT_SENT));
        }

        @Test
        void shouldClassifyAServerThatDiesAfterReadingTheCallAsMaybeDelivered() {
            StdioMcpConnection open = open("--exit-on", "tools/call");

            assertThatThrownBy(
                            () ->
                                    open.callTool(
                                            "echo", Map.of("message", "hi"), CallControl.none()))
                    .satisfies(
                            failure ->
                                    assertThat(deliveryOf(failure))
                                            .isEqualTo(McpException.Delivery.MAYBE_DELIVERED));
        }
    }

    /// The race between a caller giving up and its exchange's thread starting.
    ///
    /// `NOT_SENT` on a cancel or an expired budget rests on one promise: the
    /// stop and the start never both win. A stop that answers true for a send
    /// that went ahead would let a caller repeat a call the server ran.
    @Nested
    class RacingTheSend {

        @Test
        void shouldKeepASendStoppedBeforeItBeganFromStarting() {
            HttpExchanger.Sender sender = new HttpExchanger.Sender();

            assertThat(sender.stop()).as("nothing was handed to the client").isTrue();
            assertThat(sender.begin()).as("the stopped send must not start").isFalse();
            assertThat(sender.stop()).as("a repeated stop gives the same answer").isTrue();
        }

        @Test
        void shouldInterruptASendThatAlreadyBegan() {
            HttpExchanger.Sender sender = new HttpExchanger.Sender();

            assertThat(sender.begin()).isTrue();
            assertThat(sender.stop()).as("the request may have left").isFalse();
            assertThat(Thread.interrupted()).as("the sending thread is interrupted").isTrue();
        }

        @Test
        void shouldLetExactlyOneSideWinEveryRace() throws InterruptedException {
            for (int trial = 0; trial < 2_000; trial++) {
                HttpExchanger.Sender sender = new HttpExchanger.Sender();
                CountDownLatch go = new CountDownLatch(1);
                AtomicBoolean began = new AtomicBoolean();
                Thread sending =
                        Thread.ofVirtual()
                                .start(
                                        () -> {
                                            awaitQuietly(go);
                                            began.set(sender.begin());
                                        });
                go.countDown();
                boolean unsent = sender.stop();
                sending.join();

                assertThat(unsent)
                        .as(
                                "trial %d: stop reports unsent exactly when the send never began",
                                trial)
                        .isNotEqualTo(began.get());
            }
        }

        private static void awaitQuietly(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
