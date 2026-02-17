package io.hensu.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SseMcpConnectionTest {

    private McpSessionManager sessionManager;
    private JsonRpc jsonRpc;
    private SseMcpConnection connection;

    @BeforeEach
    void setUp() {
        sessionManager = mock(McpSessionManager.class);
        jsonRpc = mock(JsonRpc.class);
        when(sessionManager.isConnected("client-1")).thenReturn(true);
        connection = new SseMcpConnection("client-1", sessionManager, jsonRpc);
    }

    @Nested
    class ListTools {

        @Test
        void shouldReturnToolDescriptors() {
            String responseJson = "{\"result\":{\"tools\":[]}}";
            when(sessionManager.sendRequest(eq("client-1"), eq("tools/list"), any()))
                    .thenReturn(Uni.createFrom().item(responseJson));

            List<Map<String, Object>> toolsList =
                    List.of(Map.of("name", "search", "description", "Search the web"));
            when(jsonRpc.parseResult(responseJson)).thenReturn(Map.of("tools", toolsList));

            List<McpConnection.McpToolDescriptor> tools = connection.listTools();

            assertThat(tools).hasSize(1);
            assertThat(tools.getFirst().name()).isEqualTo("search");
            assertThat(tools.getFirst().description()).isEqualTo("Search the web");
        }

        @Test
        void shouldReturnEmptyListWhenNoTools() {
            String responseJson = "{\"result\":{}}";
            when(sessionManager.sendRequest(eq("client-1"), eq("tools/list"), any()))
                    .thenReturn(Uni.createFrom().item(responseJson));
            when(jsonRpc.parseResult(responseJson)).thenReturn(Map.of());

            List<McpConnection.McpToolDescriptor> tools = connection.listTools();
            assertThat(tools).isEmpty();
        }

        @Test
        void shouldThrowWhenNotConnected() {
            when(sessionManager.isConnected("client-1")).thenReturn(false);

            assertThatThrownBy(() -> connection.listTools())
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("Client not connected");
        }
    }

    @Nested
    class CallTool {

        @Test
        void shouldCallToolAndReturnResult() {
            String responseJson = "{\"result\":{\"content\":\"data\"}}";
            when(sessionManager.sendRequest(eq("client-1"), eq("tools/call"), any()))
                    .thenReturn(Uni.createFrom().item(responseJson));
            when(jsonRpc.parseResult(responseJson)).thenReturn(Map.of("content", "data"));

            Map<String, Object> result =
                    connection.callTool("read_file", Map.of("path", "/tmp/test"));

            assertThat(result).containsEntry("content", "data");
            verify(sessionManager).sendRequest(eq("client-1"), eq("tools/call"), any());
        }

        @Test
        void shouldHandleNullArguments() {
            String responseJson = "{\"result\":{}}";
            when(sessionManager.sendRequest(eq("client-1"), eq("tools/call"), any()))
                    .thenReturn(Uni.createFrom().item(responseJson));
            when(jsonRpc.parseResult(responseJson)).thenReturn(Map.of());

            assertDoesNotThrow(() -> connection.callTool("list_files", null));
        }

        @Test
        void shouldThrowWhenNotConnected() {
            when(sessionManager.isConnected("client-1")).thenReturn(false);

            assertThatThrownBy(() -> connection.callTool("test", Map.of()))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("Client not connected");
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void shouldReturnSseEndpoint() {
            assertThat(connection.getEndpoint()).isEqualTo("sse://client-1");
        }

        @Test
        void shouldDelegateIsConnected() {
            when(sessionManager.isConnected("client-1")).thenReturn(true);
            assertThat(connection.isConnected()).isTrue();

            when(sessionManager.isConnected("client-1")).thenReturn(false);
            assertThat(connection.isConnected()).isFalse();
        }

        @Test
        void shouldDisconnectOnClose() {
            connection.close();
            verify(sessionManager).disconnect("client-1");
        }
    }

    @Nested
    class Constructor {

        @Test
        void shouldRejectNullClientId() {
            assertThatThrownBy(() -> new SseMcpConnection(null, sessionManager, jsonRpc))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullSessionManager() {
            assertThatThrownBy(() -> new SseMcpConnection("client-1", null, jsonRpc))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldRejectNullJsonRpc() {
            assertThatThrownBy(() -> new SseMcpConnection("client-1", sessionManager, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
