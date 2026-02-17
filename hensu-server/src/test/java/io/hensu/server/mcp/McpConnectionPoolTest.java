package io.hensu.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class McpConnectionPoolTest {

    private McpConnectionFactory connectionFactory;
    private McpSessionManager sessionManager;
    private JsonRpc jsonRpc;
    private McpConnectionPool pool;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(McpConnectionFactory.class);
        sessionManager = mock(McpSessionManager.class);
        jsonRpc = mock(JsonRpc.class);
        pool =
                new McpConnectionPool(
                        connectionFactory,
                        sessionManager,
                        jsonRpc,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60));
    }

    @Nested
    class GetHttp {

        @Test
        void shouldCreateConnectionViaFactory() {
            McpConnection mockConn = mock(McpConnection.class);
            when(connectionFactory.create(eq("http://mcp:3000"), any(), any()))
                    .thenReturn(mockConn);

            McpConnection conn = pool.get("http://mcp:3000");

            assertThat(conn).isSameAs(mockConn);
            verify(connectionFactory).create(eq("http://mcp:3000"), any(), any());
        }

        @Test
        void shouldCacheOnSecondCall() {
            McpConnection mockConn = mock(McpConnection.class);
            when(connectionFactory.create(eq("http://mcp:3000"), any(), any()))
                    .thenReturn(mockConn);

            McpConnection first = pool.get("http://mcp:3000");
            McpConnection second = pool.get("http://mcp:3000");

            assertThat(first).isSameAs(second);
        }

        @Test
        void shouldWrapFactoryException() {
            when(connectionFactory.create(any(), any(), any()))
                    .thenThrow(new McpException("connection refused"));

            assertThatThrownBy(() -> pool.get("http://bad:3000")).isInstanceOf(McpException.class);
        }
    }

    @Nested
    class GetSse {

        @Test
        void shouldRouteSseEndpointToSessionManager() {
            when(sessionManager.isConnected("client-1")).thenReturn(true);

            McpConnection conn = pool.get("sse://client-1");

            assertThat(conn).isInstanceOf(SseMcpConnection.class);
            assertThat(conn.getEndpoint()).isEqualTo("sse://client-1");
        }

        @Test
        void shouldThrowWhenClientNotConnected() {
            when(sessionManager.isConnected("client-1")).thenReturn(false);

            assertThatThrownBy(() -> pool.get("sse://client-1"))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("Client not connected");
        }
    }

    @Nested
    class GetForTenant {

        @Test
        void shouldDelegateWithSsePrefix() {
            when(sessionManager.isConnected("tenant-1")).thenReturn(true);

            McpConnection conn = pool.getForTenant("tenant-1");

            assertThat(conn.getEndpoint()).isEqualTo("sse://tenant-1");
        }
    }

    @Nested
    class Remove {

        @Test
        void shouldRemoveAndCloseConnection() {
            McpConnection mockConn = mock(McpConnection.class);
            when(connectionFactory.create(any(), any(), any())).thenReturn(mockConn);

            pool.get("http://mcp:3000");
            pool.remove("http://mcp:3000");

            verify(mockConn).close();
            assertThat(pool.size()).isEqualTo(0);
        }

        @Test
        void shouldNoOpForUnknownEndpoint() {
            assertDoesNotThrow(() -> pool.remove("http://unknown:3000"));
        }
    }

    @Nested
    class CloseAll {

        @Test
        void shouldCloseAllAndClearPool() {
            McpConnection conn1 = mock(McpConnection.class);
            McpConnection conn2 = mock(McpConnection.class);
            when(connectionFactory.create(eq("http://a:3000"), any(), any())).thenReturn(conn1);
            when(connectionFactory.create(eq("http://b:3000"), any(), any())).thenReturn(conn2);

            pool.get("http://a:3000");
            pool.get("http://b:3000");
            assertThat(pool.size()).isEqualTo(2);

            pool.closeAll();

            verify(conn1).close();
            verify(conn2).close();
            assertThat(pool.size()).isEqualTo(0);
        }
    }

    @Nested
    class Size {

        @Test
        void shouldReturnCachedHttpConnectionCount() {
            McpConnection mockConn = mock(McpConnection.class);
            when(connectionFactory.create(any(), any(), any())).thenReturn(mockConn);

            assertThat(pool.size()).isEqualTo(0);
            pool.get("http://mcp:3000");
            assertThat(pool.size()).isEqualTo(1);
        }
    }
}
