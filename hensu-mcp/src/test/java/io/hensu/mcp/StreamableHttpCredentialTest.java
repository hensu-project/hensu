package io.hensu.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Exercises the {@link BearerCredential} seam against a real loopback endpoint.
///
/// What can go wrong here is costly in both directions: a resend loop hammers a
/// server that will never accept the token, a resend after a `403` repeats a
/// request the server already decided about, and a token in a message reaches
/// every log that captures it.
class StreamableHttpCredentialTest {

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

    private StreamableHttpMcpConnection open(BearerCredential credential) {
        connection =
                StreamableHttpMcpConnection.open(
                        McpServerSpec.Http.of("acme", server.uri()),
                        credential,
                        Set.of(server.host()),
                        client);
        return connection;
    }

    private List<String> authorizations() {
        return server.requests().stream().map(request -> request.header("authorization")).toList();
    }

    @Test
    void shouldResendOnceWithTheSuccessorOfARefusedToken() {
        server.requiringBearer("rotated");
        Rotating credential = new Rotating("stale", "rotated");

        String rendered =
                McpResultRenderer.render(
                        open(credential)
                                .callTool(
                                        "read_file",
                                        Map.of("path", "/etc/hosts"),
                                        CallControl.none()));

        assertThat(rendered).isEqualTo("file data over http");
        assertThat(credential.rejections()).isEqualTo(1);
        assertThat(authorizations())
                .as("only the very first request carries the refused token")
                .containsExactly("Bearer stale", "Bearer rotated", "Bearer rotated");
    }

    @Test
    void shouldApplyARotationThatHappensAfterTheConnectionOpened() {
        server.requiringBearer("first");
        Rotating credential = new Rotating("first", "second");
        StreamableHttpMcpConnection open = open(credential);

        server.requiringBearer("second");
        open.callTool("read_file", Map.of("path", "/etc/hosts"), CallControl.none());

        assertThat(credential.rejections()).isEqualTo(1);
        assertThat(authorizations().getLast()).isEqualTo("Bearer second");
    }

    @Test
    void shouldFailWithoutASecondResendWhenTheSuccessorIsRefusedToo() {
        server.requiringBearer("never-issued");
        Rotating credential = new Rotating("stale", "also-stale");

        assertThatThrownBy(() -> open(credential))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("rejected the bearer token")
                .hasMessageNotContaining("stale")
                .extracting(failure -> ((McpException) failure).delivery())
                .isEqualTo(McpException.Delivery.NOT_SENT);
        assertThat(authorizations()).containsExactly("Bearer stale", "Bearer also-stale");
    }

    @Test
    void shouldNotResendWhenTheCredentialHasNoSuccessor() {
        server.requiringBearer("expected");

        assertThatThrownBy(() -> open(BearerCredential.fixed("wrong")))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("rejected the bearer token");
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void shouldNeverRetryAForbiddenRequest() {
        server.forbidding();
        Rotating credential = new Rotating("accepted", "other");

        assertThatThrownBy(() -> open(credential))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("HTTP 403")
                .extracting(failure -> ((McpException) failure).delivery())
                .isEqualTo(McpException.Delivery.NOT_SENT);
        assertThat(server.requests()).hasSize(1);
        assertThat(credential.rejections()).isZero();
    }

    @Test
    void shouldNameNoTokenWhenDescribingACredential() {
        assertThat(BearerCredential.fixed("sk-live-secret").toString())
                .doesNotContain("sk-live-secret");
        assertThat(BearerCredential.fixed(" ").current())
                .as("a blank token is no token, so no empty Authorization header is sent")
                .isEmpty();
    }

    /// A secret that rotates once: the first token, and the one a refusal of it yields.
    private static final class Rotating implements BearerCredential {

        private final String successor;
        private final AtomicInteger rejections = new AtomicInteger();
        private volatile String current;

        Rotating(String first, String successor) {
            this.current = first;
            this.successor = successor;
        }

        @Override
        public Optional<String> current() {
            return Optional.of(current);
        }

        @Override
        public Optional<String> afterRejection(String rejected) {
            rejections.incrementAndGet();
            if (successor.equals(rejected)) {
                return Optional.empty();
            }
            current = successor;
            return Optional.of(successor);
        }

        int rejections() {
            return rejections.get();
        }
    }
}
