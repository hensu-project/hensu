package io.hensu.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.mcp.JsonRpc;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class McpConnectionsTest {

    private McpSessionManager sessionManager;
    private McpConnections connections;

    @BeforeEach
    void setUp() {
        sessionManager = mock(McpSessionManager.class);
        connections = new McpConnections(sessionManager, mock(JsonRpc.class));
    }

    @Nested
    class GetSse {

        @Test
        void shouldRouteSseEndpointToSessionManager() {
            when(sessionManager.isConnected("client-1")).thenReturn(true);

            McpConnection conn = connections.get("sse://client-1");

            assertThat(conn).isInstanceOf(SseMcpConnection.class);
            assertThat(conn.getEndpoint()).isEqualTo("sse://client-1");
        }

        /// The message used to be a local "Client not connected via SSE"; it now
        /// comes from the session manager, which names the owning node when the
        /// session is held elsewhere, so both connection paths report alike.
        @Test
        void shouldThrowSessionManagersDiagnosisWhenClientNotConnected() {
            McpException diagnosis =
                    new McpException(
                            "Tenant 'client-1' has its MCP session on node node-b, not on this"
                                    + " node node-a");
            when(sessionManager.isConnected("client-1")).thenReturn(false);
            when(sessionManager.notConnected("client-1")).thenReturn(diagnosis);

            assertThatThrownBy(() -> connections.get("sse://client-1")).isSameAs(diagnosis);
        }
    }

    @Nested
    class UnsupportedTransport {

        @Test
        void shouldRefuseOutboundHttpAndNameTheContract() {
            assertThatThrownBy(() -> connections.get("https://mcp.acme.test/mcp"))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("https://mcp.acme.test/mcp")
                    .hasMessageContaining("split-pipe transport only");
        }

        @Test
        void shouldRefuseNullEndpoint() {
            assertThatThrownBy(() -> connections.get(null))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("split-pipe transport only");
        }
    }
}
