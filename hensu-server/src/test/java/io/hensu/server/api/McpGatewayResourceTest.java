package io.hensu.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.server.mcp.McpSessionManager;
import io.hensu.server.mcp.McpSessionManager.ClientInfo;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class McpGatewayResourceTest {

    private McpSessionManager sessionManager;
    private McpGatewayResource resource;

    @BeforeEach
    void setUp() {
        sessionManager = mock(McpSessionManager.class);
        resource = new McpGatewayResource(sessionManager);
    }

    @Nested
    class Connect {

        @Test
        void shouldCreateSessionForValidClientId() {
            Multi<String> mockStream = Multi.createFrom().items("{\"test\":\"ping\"}");
            when(sessionManager.createSession("client-1")).thenReturn(mockStream);

            Multi<String> result = resource.connect("client-1");

            assertThat(result).isNotNull();
            verify(sessionManager).createSession("client-1");
        }

        @Test
        void shouldStreamEventsFromSessionManager() {
            Multi<String> mockStream =
                    Multi.createFrom()
                            .items("{\"method\":\"ping\"}", "{\"method\":\"tools/call\"}");
            when(sessionManager.createSession("client-1")).thenReturn(mockStream);

            Multi<String> result = resource.connect("client-1");

            AssertSubscriber<String> subscriber =
                    result.subscribe().withSubscriber(AssertSubscriber.create(10));
            subscriber.awaitCompletion();

            assertThat(subscriber.getItems()).hasSize(2);
            assertThat(subscriber.getItems().getFirst()).contains("ping");
        }
    }

    @Nested
    class ReceiveMessage {

        @Test
        void shouldAcceptValidMessage() {
            String jsonMessage = "{\"jsonrpc\":\"2.0\",\"id\":\"123\",\"result\":{}}";

            try (Response response = resource.receiveMessage(jsonMessage).await().indefinitely()) {

                assertThat(response.getStatus()).isEqualTo(204);
            }
            verify(sessionManager).handleResponse(jsonMessage);
        }
    }

    @Nested
    class Status {

        @Test
        void shouldReturnGatewayStatus() {
            when(sessionManager.connectedClientCount()).thenReturn(5);
            when(sessionManager.pendingRequestCount()).thenReturn(12);

            Map<String, Object> entity;
            try (Response response = resource.status()) {

                assertThat(response.getStatus()).isEqualTo(200);
                entity = (Map<String, Object>) response.getEntity();
            }
            assertThat(entity.get("connectedClients")).isEqualTo(5);
            assertThat(entity.get("pendingRequests")).isEqualTo(12);
        }
    }

    @Nested
    class ClientStatus {

        @Test
        void shouldReturnConnectedStatusForActiveClient() {
            when(sessionManager.isConnected("client-1")).thenReturn(true);
            when(sessionManager.getClientInfo("client-1"))
                    .thenReturn(new ClientInfo("client-1", System.currentTimeMillis() - 5000));

            Map<String, Object> entity;
            try (Response response = resource.clientStatus("client-1")) {

                assertThat(response.getStatus()).isEqualTo(200);
                entity = (Map<String, Object>) response.getEntity();
            }
            assertThat(entity.get("clientId")).isEqualTo("client-1");
            assertThat(entity.get("connected")).isEqualTo(true);
            assertThat((Long) entity.get("connectedDurationMs")).isGreaterThanOrEqualTo(5000L);
        }

        @Test
        void shouldReturnDisconnectedStatusForInactiveClient() {
            when(sessionManager.isConnected("client-2")).thenReturn(false);
            when(sessionManager.getClientInfo("client-2")).thenReturn(null);

            Map<String, Object> entity;
            try (Response response = resource.clientStatus("client-2")) {

                assertThat(response.getStatus()).isEqualTo(200);
                entity = (Map<String, Object>) response.getEntity();
            }
            assertThat(entity.get("clientId")).isEqualTo("client-2");
            assertThat(entity.get("connected")).isEqualTo(false);
        }
    }
}
