package io.hensu.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.server.mcp.McpSessionManager;
import io.hensu.server.mcp.McpSessionManager.ClientInfo;
import io.hensu.server.mcp.McpSessionManager.ResponseOutcome;
import io.hensu.server.security.RequestTenantResolver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/// Every endpoint but `/mcp/status` now resolves the caller's tenant, so each
/// test runs as the authenticated tenant `client-1` via a mocked resolver. The
/// session manager is keyed by that tenant rather than by the request, which is
/// why the expectations below name the tenant where they used to name a
/// caller-supplied id.
class McpGatewayResourceTest {

    private static final String TENANT = "client-1";

    private McpSessionManager sessionManager;
    private McpGatewayResource resource;

    @BeforeEach
    void setUp() {
        sessionManager = mock(McpSessionManager.class);
        RequestTenantResolver tenantResolver = mock(RequestTenantResolver.class);
        when(tenantResolver.tenantId()).thenReturn(TENANT);
        resource = new McpGatewayResource(sessionManager, tenantResolver);
    }

    @Nested
    class Connect {

        @Test
        void shouldStreamEventsFromSessionManager() {
            Multi<String> mockStream =
                    Multi.createFrom()
                            .items("{\"method\":\"ping\"}", "{\"method\":\"tools/call\"}");
            when(sessionManager.createSession(TENANT)).thenReturn(mockStream);

            Multi<String> result = resource.connect(TENANT);

            AssertSubscriber<String> subscriber =
                    result.subscribe().withSubscriber(AssertSubscriber.create(10));
            subscriber.awaitCompletion();

            assertThat(subscriber.getItems()).hasSize(2);
            assertThat(subscriber.getItems().getFirst()).contains("ping");
        }

        @Test
        void shouldRegisterUnderTenantClaimWhenClientIdOmitted() {
            when(sessionManager.createSession(TENANT)).thenReturn(Multi.createFrom().empty());

            resource.connect(null);

            verify(sessionManager).createSession(TENANT);
        }

        /// Registering as another tenant's client would route that tenant's tool
        /// requests to the caller, so a disagreeing id is refused outright.
        @Test
        void shouldRefuseClientIdThatDisagreesWithTenantClaim() {
            assertThatThrownBy(() -> resource.connect("someone-else"))
                    .isInstanceOf(ForbiddenException.class);

            verify(sessionManager, never()).createSession(anyString());
        }
    }

    @Nested
    class ReceiveMessage {

        @Test
        void shouldAcceptValidMessage() {
            String jsonMessage = "{\"jsonrpc\":\"2.0\",\"id\":\"123\",\"result\":{}}";
            when(sessionManager.handleResponse(TENANT, jsonMessage))
                    .thenReturn(ResponseOutcome.COMPLETED);

            try (Response response = resource.receiveMessage(jsonMessage).await().indefinitely()) {

                assertThat(response.getStatus()).isEqualTo(204);
            }
            verify(sessionManager).handleResponse(TENANT, jsonMessage);
        }

        @Test
        void shouldAcceptNotificationWithoutId() {
            String jsonMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}";
            when(sessionManager.handleResponse(TENANT, jsonMessage))
                    .thenReturn(ResponseOutcome.NOT_A_RESPONSE);

            try (Response response = resource.receiveMessage(jsonMessage).await().indefinitely()) {

                assertThat(response.getStatus()).isEqualTo(204);
            }
        }

        /// An id the caller does not own – including one owned by another tenant –
        /// is a 404, so the endpoint never confirms another tenant's request exists.
        @Test
        void shouldAnswerNotFoundForIdTheCallerDoesNotOwn() {
            String jsonMessage = "{\"jsonrpc\":\"2.0\",\"id\":\"foreign\",\"result\":{}}";
            when(sessionManager.handleResponse(TENANT, jsonMessage))
                    .thenReturn(ResponseOutcome.UNKNOWN);

            assertThatThrownBy(() -> resource.receiveMessage(jsonMessage))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Status {

        @Test
        void shouldReturnGatewayStatus() {
            when(sessionManager.connectedClientCount()).thenReturn(5);
            when(sessionManager.pendingRequestCount()).thenReturn(12);

            try (Response response = resource.status()) {

                assertThat(response.getStatus()).isEqualTo(200);
                var entity = (GatewayStatusResponse) response.getEntity();
                assertThat(entity.connectedClients()).isEqualTo(5);
                assertThat(entity.pendingRequests()).isEqualTo(12);
            }
        }
    }

    @Nested
    class ClientStatus {

        @Test
        void shouldReturnConnectedStatusForActiveClient() {
            when(sessionManager.isConnected("client-1")).thenReturn(true);
            when(sessionManager.getClientInfo("client-1"))
                    .thenReturn(new ClientInfo("client-1", System.currentTimeMillis() - 5000));

            try (Response response = resource.clientStatus("client-1")) {

                assertThat(response.getStatus()).isEqualTo(200);
                var entity = (ClientStatusResponse) response.getEntity();
                assertThat(entity.clientId()).isEqualTo("client-1");
                assertThat(entity.connected()).isTrue();
                assertThat(entity.connectedDurationMs()).isGreaterThanOrEqualTo(5000L);
            }
        }

        @Test
        void shouldReturnDisconnectedStatusForInactiveClient() {
            when(sessionManager.isConnected(TENANT)).thenReturn(false);
            when(sessionManager.getClientInfo(TENANT)).thenReturn(null);

            try (Response response = resource.clientStatus(TENANT)) {

                assertThat(response.getStatus()).isEqualTo(200);
                var entity = (ClientStatusResponse) response.getEntity();
                assertThat(entity.clientId()).isEqualTo(TENANT);
                assertThat(entity.connected()).isFalse();
                assertThat(entity.connectedDurationMs()).isNull();
            }
        }

        /// Previously any caller could read any client's status; it now answers
        /// only for the caller's own tenant, and 404 for every other.
        @Test
        void shouldAnswerNotFoundForAnotherTenantsClient() {
            assertThatThrownBy(() -> resource.clientStatus("client-2"))
                    .isInstanceOf(NotFoundException.class);

            verify(sessionManager, never()).getClientInfo(anyString());
        }
    }
}
