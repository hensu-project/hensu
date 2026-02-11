package io.hensu.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class McpSessionManagerTest {

    private McpSessionManager sessionManager;
    private JsonRpc jsonRpc;

    @BeforeEach
    void setUp() {
        jsonRpc = new JsonRpc(new ObjectMapper());
        sessionManager = new McpSessionManager(jsonRpc);
    }

    @Nested
    class CreateSession {

        @Test
        void shouldCreateSessionAndEmitPing() {
            AssertSubscriber<String> subscriber =
                    sessionManager
                            .createSession("client-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            // Should receive ping immediately
            subscriber.awaitItems(1);
            assertThat(subscriber.getItems()).hasSize(1);
            assertThat(subscriber.getItems().getFirst()).contains("\"method\":\"ping\"");
        }

        @Test
        void shouldMarkClientAsConnected() {
            sessionManager
                    .createSession("client-1")
                    .subscribe()
                    .withSubscriber(AssertSubscriber.create(10));

            assertThat(sessionManager.isConnected("client-1")).isTrue();
            assertThat(sessionManager.connectedClientCount()).isEqualTo(1);
        }

        @Test
        void shouldTrackClientInfo() {
            sessionManager
                    .createSession("client-1")
                    .subscribe()
                    .withSubscriber(AssertSubscriber.create(10));

            McpSessionManager.ClientInfo info = sessionManager.getClientInfo("client-1");
            assertThat(info).isNotNull();
            assertThat(info.clientId()).isEqualTo("client-1");
            assertThat(info.connectedAt()).isGreaterThan(0);
        }
    }

    @Nested
    class SendRequest {

        @Test
        void shouldSendRequestAndReceiveResponse() {
            // Connect client
            AssertSubscriber<String> subscriber =
                    sessionManager
                            .createSession("client-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            // Await ping
            subscriber.awaitItems(1);

            // Send request in background
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CompletableFuture<String> resultFuture =
                    CompletableFuture.supplyAsync(
                            () ->
                                    sessionManager
                                            .sendRequest(
                                                    "client-1",
                                                    "tools/call",
                                                    Map.of("name", "test"))
                                            .await()
                                            .atMost(Duration.ofSeconds(5)),
                            executor);

            // Wait for request to be sent
            subscriber.awaitItems(2);
            String request = subscriber.getItems().get(1);
            assertThat(request).contains("\"method\":\"tools/call\"");

            // Extract request ID and send response
            String requestId = jsonRpc.extractId(request);
            String response = jsonRpc.createResponse(requestId, Map.of("result", "success"));
            sessionManager.handleResponse(response);

            // Verify result
            String result = resultFuture.join();
            assertThat(result).contains("\"result\"");
        }

        @Test
        void shouldFailIfClientNotConnected() {
            Uni<String> result = sessionManager.sendRequest("unknown", "test", Map.of());

            assertThatThrownBy(() -> result.await().atMost(Duration.ofSeconds(1)))
                    .hasStackTraceContaining("not connected");
        }

        @Test
        void shouldTimeoutIfNoResponse() {
            sessionManager
                    .createSession("client-1")
                    .subscribe()
                    .withSubscriber(AssertSubscriber.create(10));

            Uni<String> result =
                    sessionManager.sendRequest(
                            "client-1", "test", Map.of(), Duration.ofMillis(100));

            assertThatThrownBy(() -> result.await().atMost(Duration.ofSeconds(1)))
                    .hasStackTraceContaining("timed out");
        }
    }

    @Nested
    class SendNotification {

        @Test
        void shouldSendNotificationWithoutWaiting() {
            AssertSubscriber<String> subscriber =
                    sessionManager
                            .createSession("client-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            subscriber.awaitItems(1); // ping

            sessionManager.sendNotification("client-1", "progress", Map.of("percent", 50));

            subscriber.awaitItems(2);
            assertThat(subscriber.getItems().get(1)).contains("\"method\":\"progress\"");
        }
    }

    @Nested
    class HandleResponse {

        @Test
        void shouldIgnoreResponseWithoutId() {
            // Should not throw
            assertDoesNotThrow(() -> sessionManager.handleResponse("{}"));
        }

        @Test
        void shouldIgnoreResponseForUnknownId() {
            String response = jsonRpc.createResponse("unknown-id", Map.of());
            assertDoesNotThrow(() -> sessionManager.handleResponse(response));
        }
    }

    @Nested
    class Disconnect {

        @Test
        void shouldRemoveClientOnDisconnect() {
            AssertSubscriber<String> subscriber =
                    sessionManager
                            .createSession("client-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            assertThat(sessionManager.isConnected("client-1")).isTrue();

            sessionManager.disconnect("client-1");

            assertThat(sessionManager.isConnected("client-1")).isFalse();
        }

        @Test
        void shouldCompleteStreamOnDisconnect() {
            AssertSubscriber<String> subscriber =
                    sessionManager
                            .createSession("client-1")
                            .subscribe()
                            .withSubscriber(AssertSubscriber.create(10));

            sessionManager.disconnect("client-1");

            assertDoesNotThrow(() -> subscriber.awaitCompletion(Duration.ofSeconds(1)));
        }
    }
}
