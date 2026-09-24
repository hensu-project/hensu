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

        @Test
        void shouldThrowWhenClientNotConnected() {
            when(sessionManager.isConnected("client-1")).thenReturn(false);

            assertThatThrownBy(() -> connections.get("sse://client-1"))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("Client not connected");
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
