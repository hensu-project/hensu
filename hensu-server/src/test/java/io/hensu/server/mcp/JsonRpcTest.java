package io.hensu.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JsonRpcTest {

    private JsonRpc jsonRpc;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        jsonRpc = new JsonRpc(mapper);
    }

    @Nested
    class CreateRequest {

        @Test
        void shouldCreateValidRequest() throws Exception {
            String json = jsonRpc.createRequest("req-1", "tools/call", Map.of("name", "search"));

            JsonNode node = mapper.readTree(json);
            assertThat(node.get("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(node.get("id").asText()).isEqualTo("req-1");
            assertThat(node.get("method").asText()).isEqualTo("tools/call");
            assertThat(node.get("params").get("name").asText()).isEqualTo("search");
        }
    }

    @Nested
    class CreateNotification {

        @Test
        void shouldCreateNotificationWithoutId() throws Exception {
            String json = jsonRpc.createNotification("initialized", Map.of());

            JsonNode node = mapper.readTree(json);
            assertThat(node.get("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(node.get("method").asText()).isEqualTo("initialized");
            assertThat(node.has("id")).isFalse();
        }
    }

    @Nested
    class CreateResponse {

        @Test
        void shouldCreateSuccessResponse() throws Exception {
            String json = jsonRpc.createResponse("req-1", Map.of("tools", List.of()));

            JsonNode node = mapper.readTree(json);
            assertThat(node.get("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(node.get("id").asText()).isEqualTo("req-1");
            assertThat(node.has("result")).isTrue();
        }
    }

    @Nested
    class CreateErrorResponse {

        @Test
        void shouldCreateErrorResponse() throws Exception {
            String json = jsonRpc.createErrorResponse("req-1", -32600, "Invalid Request");

            JsonNode node = mapper.readTree(json);
            assertThat(node.get("id").asText()).isEqualTo("req-1");
            assertThat(node.get("error").get("code").asInt()).isEqualTo(-32600);
            assertThat(node.get("error").get("message").asText()).isEqualTo("Invalid Request");
        }
    }

    @Nested
    class ExtractFields {

        @Test
        void shouldExtractId() {
            String json = jsonRpc.createRequest("req-42", "test", Map.of());
            assertThat(jsonRpc.extractId(json)).isEqualTo("req-42");
        }

        @Test
        void shouldReturnNullForMissingId() {
            String json = jsonRpc.createNotification("test", Map.of());
            assertThat(jsonRpc.extractId(json)).isNull();
        }

        @Test
        void shouldExtractMethod() {
            String json = jsonRpc.createRequest("1", "tools/list", Map.of());
            assertThat(jsonRpc.extractMethod(json)).isEqualTo("tools/list");
        }

        @Test
        void shouldReturnNullForMissingMethod() {
            String json = jsonRpc.createResponse("1", Map.of());
            assertThat(jsonRpc.extractMethod(json)).isNull();
        }

        @Test
        void shouldReturnNullForInvalidJson() {
            assertThat(jsonRpc.extractId("not json")).isNull();
            assertThat(jsonRpc.extractMethod("not json")).isNull();
        }
    }

    @Nested
    class ParseResult {

        @Test
        void shouldExtractResultMap() {
            String json = jsonRpc.createResponse("1", Map.of("key", "value"));

            Map<String, Object> result = jsonRpc.parseResult(json);
            assertThat(result).containsEntry("key", "value");
        }

        @Test
        void shouldReturnEmptyMapForNullResult() {
            String json = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":null}";

            Map<String, Object> result = jsonRpc.parseResult(json);
            assertThat(result).isEmpty();
        }

        @Test
        void shouldThrowOnErrorResponse() {
            String json = jsonRpc.createErrorResponse("1", -32601, "Method not found");

            assertThatThrownBy(() -> jsonRpc.parseResult(json))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("Method not found");
        }
    }

    @Nested
    class ParseParams {

        @Test
        void shouldExtractParams() {
            String json = jsonRpc.createRequest("1", "test", Map.of("key", "val"));

            Map<String, Object> params = jsonRpc.parseParams(json);
            assertThat(params).containsEntry("key", "val");
        }

        @Test
        void shouldReturnEmptyMapForMissingParams() {
            String json = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"test\"}";

            assertThat(jsonRpc.parseParams(json)).isEmpty();
        }
    }

    @Nested
    class MessageClassification {

        @Test
        void shouldIdentifyResponse() {
            String response = jsonRpc.createResponse("1", Map.of());
            assertThat(jsonRpc.isResponse(response)).isTrue();
            assertThat(jsonRpc.isRequest(response)).isFalse();
        }

        @Test
        void shouldIdentifyErrorAsResponse() {
            String error = jsonRpc.createErrorResponse("1", -1, "fail");
            assertThat(jsonRpc.isResponse(error)).isTrue();
        }

        @Test
        void shouldIdentifyRequest() {
            String request = jsonRpc.createRequest("1", "test", Map.of());
            assertThat(jsonRpc.isRequest(request)).isTrue();
            assertThat(jsonRpc.isResponse(request)).isFalse();
        }

        @Test
        void shouldIdentifyNotification() {
            String notification = jsonRpc.createNotification("test", Map.of());
            assertThat(jsonRpc.isRequest(notification)).isTrue();
        }

        @Test
        void shouldReturnFalseForInvalidJson() {
            assertThat(jsonRpc.isResponse("not json")).isFalse();
            assertThat(jsonRpc.isRequest("not json")).isFalse();
        }
    }
}
