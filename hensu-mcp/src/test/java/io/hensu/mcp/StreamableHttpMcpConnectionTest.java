package io.hensu.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/// Exercises the Streamable HTTP client against a real loopback endpoint.
///
/// Every case here is one the implementation can plausibly get wrong: a response
/// shape it must recognise from a header, an era it must detect rather than
/// assume, a header the server rejects the request without, a session refresh
/// that races itself, and a deadline whose expiry must cost the call rather than
/// the connection.
class StreamableHttpMcpConnectionTest {

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
            connection = null;
        }
        server.close();
    }

    private McpServerSpec.Http spec(long requestTimeoutMs) {
        return new McpServerSpec.Http(
                "acme",
                "",
                server.uri(),
                null,
                Map.of(),
                McpServerSpec.DEFAULT_STARTUP_TIMEOUT_MS,
                requestTimeoutMs,
                true,
                false);
    }

    private StreamableHttpMcpConnection open() {
        return open(spec(5_000));
    }

    private StreamableHttpMcpConnection open(McpServerSpec.Http spec) {
        connection = StreamableHttpMcpConnection.open(spec, null, Set.of(server.host()), client);
        return connection;
    }

    @Nested
    class ResponseShapes {

        @Test
        void shouldRoundTripACallAnsweredAsPlainJson() {
            String rendered =
                    McpResultRenderer.render(
                            open().callTool("read_file", Map.of("path", "/etc/hosts")));

            assertThat(rendered).isEqualTo("file data over http");
        }

        @Test
        void shouldRoundTripACallAnsweredAsAnSseStream() {
            server.streaming();

            String rendered =
                    McpResultRenderer.render(
                            open().callTool("read_file", Map.of("path", "/etc/hosts")));

            assertThat(rendered).isEqualTo("file data over http");
        }

        @Test
        void shouldOfferBothResponseShapesOnEveryPost() {
            // SSE is a response mode of this transport, not a separate transport:
            // a server may upgrade any answer, so every request must say it can
            // read one.
            open().callTool("read_file", Map.of("path", "/etc/hosts"));

            assertThat(server.requests())
                    .isNotEmpty()
                    .allSatisfy(
                            request ->
                                    assertThat(request.header("accept"))
                                            .contains("application/json")
                                            .contains("text/event-stream"));
        }
    }

    @Nested
    class ModernEra {

        @Test
        void shouldCarryTheHeadersTheRevisionRequires() {
            open().callTool("read_file", Map.of("path", "/etc/hosts"));

            FakeHttpMcpServer.Recorded call =
                    server.requests().stream()
                            .filter(request -> "tools/call".equals(request.rpcMethod()))
                            .findFirst()
                            .orElseThrow();

            assertThat(call.header(McpProtocol.HEADER_PROTOCOL_VERSION))
                    .isEqualTo(McpProtocol.MODERN_REVISION);
            assertThat(call.header(McpProtocol.HEADER_METHOD)).isEqualTo("tools/call");
            // A Mcp-Name that disagrees with the body is -32020 on the wire.
            assertThat(call.header(McpProtocol.HEADER_NAME)).isEqualTo("read_file");
        }

        @Test
        void shouldCarryClientIdentityInMetaRatherThanAHandshake() {
            open().listTools();

            FakeHttpMcpServer.Recorded probe = server.requests().getFirst();

            assertThat(probe.body())
                    .contains(McpProtocol.META_PROTOCOL_VERSION)
                    .contains(McpProtocol.META_CLIENT_INFO)
                    .contains(McpProtocol.META_CLIENT_CAPABILITIES);
            assertThat(server.initializeCount()).isZero();
        }

        @Test
        void shouldNotSendMcpNameOnAMethodThatHasNoTarget() {
            open().listTools();

            assertThat(server.requests().getFirst().header(McpProtocol.HEADER_NAME)).isNull();
        }
    }

    @Nested
    class EraProbe {

        @Test
        void shouldDetectAModernServerWithoutAHandshake() {
            assertThat(open().revision()).isEqualTo(McpProtocol.MODERN_REVISION);
            assertThat(server.initializeCount()).isZero();
        }

        @Test
        void shouldFallBackToTheHandshakeWhenTheProbeIsNotUnderstood() {
            server.legacy();

            assertThat(open().revision()).isEqualTo(McpProtocol.LEGACY_REVISION);
            assertThat(server.initializeCount()).isEqualTo(1);
            assertThat(server.requests())
                    .anySatisfy(
                            request ->
                                    assertThat(request.rpcMethod())
                                            .isEqualTo("notifications/initialized"));
        }

        @Test
        void shouldRetryAtAListedRevisionWhenTheProbeIsRefused() {
            server.legacy().offeringVersions(List.of("2025-03-26", McpProtocol.LEGACY_REVISION));

            assertThat(open().revision()).isEqualTo(McpProtocol.LEGACY_REVISION);
            assertThat(server.initializeCount()).isEqualTo(1);
        }

        @Test
        void shouldFailWhenNoRevisionIsCommon() {
            server.offeringVersions(List.of("1999-01-01"));

            assertThatThrownBy(this::openFixture)
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("1999-01-01")
                    .hasMessageContaining(McpProtocol.MODERN_REVISION);
        }

        @Test
        void shouldNameTheDeprecatedTransportWhenNeitherEraAnswersAndIssueNoGet() {
            server.legacy().probeStatus(405).initializeStatus(405);

            assertThatThrownBy(this::openFixture)
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining(server.uri().toString())
                    .hasMessageContaining("HTTP+SSE");

            assertThat(server.requests())
                    .as("no GET is ever issued; the ladder that follows one is out of scope")
                    .noneSatisfy(request -> assertThat(request.method()).isEqualTo("GET"));
        }

        private void openFixture() {
            open();
        }
    }

    @Nested
    class LegacyHandshake {

        @Test
        void shouldFailAtLaunchOnAnUnsupportedRevisionAndListNoTools() {
            server.legacy().announcing("1999-01-01");

            assertThatThrownBy(StreamableHttpMcpConnectionTest.this::open)
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("1999-01-01")
                    .hasMessageContaining(McpProtocol.LEGACY_REVISION);

            assertThat(legacyToolListings()).isEmpty();
        }

        @Test
        void shouldSayNoToolsRatherThanCallAServerThatAdvertisesNone() {
            server.legacy().declaringTools(false);

            StreamableHttpMcpConnection open = open();

            assertThat(open.listTools()).isEmpty();
            assertThat(open.catalogNotices())
                    .singleElement()
                    .asString()
                    .contains("advertises no tools capability");
            assertThat(legacyToolListings()).isEmpty();
        }

        /// `tools/list` requests in the legacy shape.
        ///
        /// The era probe is itself a `tools/list`, distinguished only by the
        /// modern protocol header, so a bare method match would never be empty.
        private List<FakeHttpMcpServer.Recorded> legacyToolListings() {
            return server.requests().stream()
                    .filter(request -> "tools/list".equals(request.rpcMethod()))
                    .filter(request -> request.header(McpProtocol.HEADER_PROTOCOL_VERSION) == null)
                    .toList();
        }

        @Test
        void shouldNameTheServerForAnApprovalFrame() {
            server.legacy();

            assertThat(open().serverInfo()).isEqualTo("fake-http");
        }
    }

    @Nested
    class LegacySessions {

        @Test
        void shouldEchoTheSessionIdentifierOnEveryLaterRequest() {
            server.legacy().mintingSessions();

            open().callTool("read_file", Map.of("path", "/etc/hosts"));

            String minted = server.mintedSessions().getFirst();
            assertThat(server.requests())
                    .filteredOn(request -> "tools/call".equals(request.rpcMethod()))
                    .allSatisfy(
                            request ->
                                    assertThat(request.header(McpProtocol.HEADER_SESSION_ID))
                                            .isEqualTo(minted));
        }

        @Test
        void shouldReinitialiseExactlyOnceWhenASessionIsRejected() {
            server.legacy().mintingSessions();
            open();
            server.invalidateSessions();

            String rendered =
                    McpResultRenderer.render(
                            connection.callTool("read_file", Map.of("path", "/etc/hosts")));

            assertThat(rendered).isEqualTo("file data over http");
            assertThat(server.initializeCount()).isEqualTo(2);
            assertThat(server.mintedSessions()).hasSize(2);
        }

        @Test
        void shouldFailTheCallRatherThanLoopOnAServerThatRejectsEverySession() {
            server.legacy().mintingSessions();
            open();
            server.rejectingEverySession();

            assertThatThrownBy(() -> connection.callTool("read_file", Map.of("path", "/etc/hosts")))
                    .isInstanceOf(McpException.class);
            assertThat(server.initializeCount())
                    .as("one handshake at open and one refresh, never a loop")
                    .isEqualTo(2);
        }

        @Test
        void shouldReleaseTheSessionOnCloseAndTolerateA405() {
            server.legacy().mintingSessions();
            open();

            connection.close();
            connection = null;

            assertThat(server.requests())
                    .anySatisfy(request -> assertThat(request.method()).isEqualTo("DELETE"));
        }

        /// Several Virtual Threads meet one rejected session at the same moment.
        ///
        /// Written concurrently on purpose: the single-threaded form of this
        /// assertion passes against an unguarded implementation, which is the
        /// defect it exists to catch. Unguarded, every caller posts its own
        /// `initialize`, the server mints a session per caller, and all but the
        /// last leak because nothing issues a `DELETE` for a session the
        /// connection has already forgotten.
        @Test
        void shouldRefreshARejectedSessionOnceUnderConcurrentCallers() throws Exception {
            server.legacy().mintingSessions();
            open();
            server.invalidateSessions();

            int callers = 8;
            CountDownLatch ready = new CountDownLatch(callers);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(callers);
            AtomicInteger succeeded = new AtomicInteger();

            for (int i = 0; i < callers; i++) {
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    ready.countDown();
                                    try {
                                        go.await();
                                        connection.callTool(
                                                "read_file", Map.of("path", "/etc/hosts"));
                                        succeeded.incrementAndGet();
                                    } catch (Exception e) {
                                        // counted by omission
                                    } finally {
                                        done.countDown();
                                    }
                                });
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

            assertThat(succeeded).hasValue(callers);
            assertThat(server.initializeCount())
                    .as("exactly one handshake at open and one refresh")
                    .isEqualTo(2);
            assertThat(server.mintedSessions()).hasSize(2);
        }
    }

    @Nested
    class Deadlines {

        @Test
        void shouldTimeOutModernCallsWithoutAnnouncingAnything() {
            server.answeringAfter(1_500);
            open(spec(300));

            assertThatThrownBy(() -> connection.callTool("read_file", Map.of("path", "/etc/hosts")))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("timed out after 300ms");

            assertThat(server.requests())
                    .as("closing the response stream is the cancellation in this era")
                    .noneSatisfy(
                            request ->
                                    assertThat(request.rpcMethod())
                                            .isEqualTo("notifications/cancelled"));
        }

        /// The cancellation goes out, and the caller does not wait for it.
        ///
        /// The elapsed-time bound is the point of this test. Without it the
        /// assertion passes against a cancellation joined on the caller's thread,
        /// which charges an operator who asked for a 300 ms timeout the
        /// cancellation budget on top of it — the outcome
        /// `CANCELLATION_TIMEOUT_MS` exists to prevent.
        @Test
        void shouldAnnounceCancellationWithoutMakingTheCallerWaitForIt()
                throws InterruptedException {
            server.legacy().answeringAfter(1_500).readingCancellationsAfter(1_500);
            open(spec(300));

            long start = System.nanoTime();
            assertThatThrownBy(() -> connection.callTool("read_file", Map.of("path", "/etc/hosts")))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("timed out");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs)
                    .as("the caller waits its own budget, not its budget plus the cancellation's")
                    .isLessThan(1_000);

            assertThat(server.awaitCancellation(5, TimeUnit.SECONDS))
                    .as("the cancellation still reaches the server")
                    .isTrue();
        }

        @Test
        void shouldSurviveAServerThatWillNotReadTheCancellation() {
            server.legacy().answeringAfter(700).readingCancellationsAfter(3_000);
            open(spec(200));

            assertThatThrownBy(() -> connection.callTool("read_file", Map.of("path", "/etc/hosts")))
                    .isInstanceOf(McpException.class);

            // A tool-call timeout is a failed call, not a failed connection: one
            // slow cancellation must not cost the run every remaining call.
            assertThat(connection.isConnected()).isTrue();
            server.answeringAfter(0);
            assertThat(
                            McpResultRenderer.render(
                                    connection.callTool("read_file", Map.of("path", "/etc/hosts"))))
                    .isEqualTo("file data over http");
        }
    }

    @Nested
    class EgressBound {

        @Test
        void shouldRefuseARedirectToAHostNoDeclarationNamed() {
            open();
            server.redirectingTo("https://exfiltration.invalid/mcp");

            assertThatThrownBy(() -> connection.callTool("read_file", Map.of("path", "/etc/hosts")))
                    .isInstanceOf(McpEgressDeniedException.class)
                    .hasMessageContaining("exfiltration.invalid")
                    .hasMessageContaining(server.host());
        }

        /// A host being declared somewhere is not permission to receive *this*
        /// server's credential.
        ///
        /// Every request carries the bearer token and declared headers of the
        /// server it was opened for, so following a redirect to a host declared
        /// for a different server hands one server's credential to another. The
        /// allowlist bounds the run; the connection's own host bounds its token.
        @Test
        void shouldRefuseARedirectToAnotherDeclaredServersHost() {
            connection =
                    StreamableHttpMcpConnection.open(
                            new McpServerSpec.Http(
                                    "acme",
                                    "",
                                    server.uri(),
                                    null,
                                    Map.of(),
                                    McpServerSpec.DEFAULT_STARTUP_TIMEOUT_MS,
                                    5_000,
                                    true,
                                    false),
                            "sk-live-do-not-leak",
                            Set.of(server.host(), "other.declared.example"),
                            client);

            server.redirectingTo("https://other.declared.example/mcp");
            int beforeTheCall = server.requests().size();

            assertThatThrownBy(() -> connection.callTool("read_file", Map.of("path", "/etc/hosts")))
                    .isInstanceOf(McpEgressDeniedException.class)
                    .hasMessageContaining("other.declared.example")
                    .hasMessageContaining("a different server");

            assertThat(server.requests())
                    .as("the redirect is refused, not followed: exactly one POST went out")
                    .hasSize(beforeTheCall + 1);
        }
    }

    @Nested
    class TruncatedAnswers {

        @Test
        void shouldFailACallWhoseAnswerBeganAndNeverCompleted() {
            // Returning the fragment would report a truncated call as a
            // successful one, and the agent would act on half a result.
            server.truncatingAfterPartial();
            open();

            assertThatThrownBy(() -> connection.callTool("read_file", Map.of("path", "/etc/hosts")))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("before the result was complete");
        }
    }

    /// A refused credential is an answer from a reachable server, and the operator's
    /// fix depends on whether a token was sent at all.
    @Nested
    class AccessRefused {

        @Test
        void shouldSayAuthorizationIsRequiredWhenNoTokenWasSent() {
            server.requiringBearer("expected-token");

            assertThatThrownBy(StreamableHttpMcpConnectionTest.this::open)
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("HTTP 401")
                    .hasMessageContaining("requires authorization and none was sent")
                    .hasMessageNotContaining("reach");
        }

        @Test
        void shouldSayTheTokenWasRejectedWithoutRepeatingIt() {
            server.requiringBearer("expected-token");

            assertThatThrownBy(
                            () ->
                                    StreamableHttpMcpConnection.open(
                                            spec(5_000),
                                            "wrong-token-value",
                                            Set.of(server.host()),
                                            client))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("rejected the bearer token")
                    .hasMessageNotContaining("wrong-token-value");
        }

        @Test
        void shouldNotMistakeALegacyServersRefusalForAnUnknownEra() {
            // A legacy server that answers the modern probe 401 must not be sent down the
            // fallback path to be misreported as speaking neither revision.
            server.legacy().requiringBearer("expected-token");

            assertThatThrownBy(StreamableHttpMcpConnectionTest.this::open)
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("HTTP 401")
                    .hasMessageNotContaining("neither revision");
        }
    }

    /// The fixture validates headers against the body as a modern server must,
    /// so a call that succeeds here is one a real gateway would have accepted.
    @Nested
    class ParameterHeaders {

        private final Map<String, Object> regionSchema =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "path",
                                Map.of("type", "string"),
                                "region",
                                Map.of("type", "string", "x-mcp-header", "Region")));

        private List<FakeHttpMcpServer.Recorded> calls() {
            return server.requests().stream()
                    .filter(request -> "tools/call".equals(request.rpcMethod()))
                    .toList();
        }

        @Test
        void shouldMirrorAnAnnotatedArgumentTheServerAccepts() {
            server.publishingSchema(regionSchema);

            String rendered =
                    McpResultRenderer.render(
                            open().callTool("read_file", Map.of("path", "/x", "region", "Zürich")));

            assertThat(rendered).isEqualTo("file data over http");
            assertThat(calls().getFirst().header("mcp-param-region"))
                    .isEqualTo("=?base64?WsO8cmljaA==?=");
        }

        @Test
        void shouldCarryANameThatIsNotHeaderSafe() {
            // Before encoding, the JDK client refused this header outright, so
            // a server publishing a non-ASCII tool name had tools nobody could call.
            server.publishingTool("grüße", Map.of("type", "object"));

            open().callTool("grüße", Map.of());

            assertThat(calls().getFirst().header("mcp-name")).isEqualTo("=?base64?Z3LDvMOfZQ==?=");
        }

        @Test
        void shouldLeaveOutOnlyTheToolWhoseAnnotationIsInvalid() {
            server.publishingTool(
                    "measure",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("ratio", Map.of("type", "number", "x-mcp-header", "Ratio"))));

            StreamableHttpMcpConnection open = open();

            assertThat(open.listTools())
                    .extracting(McpConnection.McpToolDescriptor::name)
                    .containsExactly("read_file");
            assertThat(open.catalogNotices())
                    .singleElement()
                    .asString()
                    .contains("'measure'")
                    .contains("type is number");
        }

        @Test
        void shouldRelistAndRetryWhenTheServerChangedAnnotationsSinceTheListing() {
            StreamableHttpMcpConnection open = open();
            server.publishingSchema(regionSchema);

            String rendered =
                    McpResultRenderer.render(
                            open.callTool("read_file", Map.of("path", "/x", "region", "eu")));

            assertThat(rendered).isEqualTo("file data over http");
            assertThat(calls()).hasSize(2);
            assertThat(calls().getFirst().header("mcp-param-region")).isNull();
            assertThat(calls().getLast().header("mcp-param-region")).isEqualTo("eu");
        }

        @Test
        void shouldSendNothingWhenAnArgumentHasNoHeaderForm() {
            server.publishingSchema(regionSchema);
            StreamableHttpMcpConnection open = open();

            assertThatThrownBy(
                            () ->
                                    open.callTool(
                                            "read_file",
                                            Map.of("path", "/x", "region", List.of("eu"))))
                    .isInstanceOf(McpInvalidArgumentException.class);
            assertThat(calls()).isEmpty();
        }

        @Test
        void shouldIgnoreAnnotationsEntirelyInTheLegacyEra() {
            // The annotation postdates the legacy revision: a server speaking it
            // validates no Mcp-Param header, so an annotation it happens to carry
            // must neither hide the tool nor add headers.
            server.legacy()
                    .publishingSchema(
                            Map.of(
                                    "type",
                                    "object",
                                    "properties",
                                    Map.of(
                                            "path",
                                            Map.of("type", "number", "x-mcp-header", "Path"))));

            StreamableHttpMcpConnection open = open();
            open.callTool("read_file", Map.of("path", 1.5));

            assertThat(open.listTools()).hasSize(1);
            assertThat(calls().getFirst().headers()).doesNotContainKey("mcp-param-path");
        }
    }

    @Nested
    class SchemaFidelity {

        @Test
        void shouldCarryTheRawSchemaThroughToTheToolDefinition() {
            server.publishingSchema(
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("mode", Map.of("enum", List.of("fast", "thorough"))),
                            "required",
                            List.of("mode")));

            var definition = McpSchemaConverter.convert(open().listTools().getFirst());

            assertThat(definition.rawSchema()).isNotNull();
            assertThat(definition.rawSchema().toString()).contains("thorough");
        }
    }

    @Test
    void shouldReportTheDeclaredEndpoint() {
        assertThat(open().getEndpoint()).isEqualTo(server.uri().toString());
        assertThat(URI.create(connection.getEndpoint()).getHost()).isEqualTo(server.host());
    }
}
