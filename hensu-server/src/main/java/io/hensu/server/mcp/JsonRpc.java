package io.hensu.server.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

/// JSON-RPC 2.0 helper for MCP protocol messages.
///
/// Creates and parses JSON-RPC messages used by the Model Context Protocol.
/// All MCP communication uses JSON-RPC 2.0 format over the split-pipe transport.
///
/// ### Message Types
/// - **Request**: Has `id`, `method`, `params` - expects a response
/// - **Notification**: Has `method`, `params` - no response expected
/// - **Response**: Has `id`, `result` or `error`
///
/// @see McpSessionManager for message routing
/// @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0 Spec</a>
@ApplicationScoped
public class JsonRpc {

    private final ObjectMapper mapper;

    @Inject
    public JsonRpc(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /// Creates a JSON-RPC request (expects a response).
    ///
    /// @param id unique request identifier for response correlation
    /// @param method the method to invoke (e.g., "tools/call")
    /// @param params method parameters
    /// @return JSON-RPC request string
    public String createRequest(String id, String method, Object params) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", id);
        root.put("method", method);
        root.putPOJO("params", params);
        return root.toString();
    }

    /// Creates a JSON-RPC notification (no response expected).
    ///
    /// @param method the method to invoke
    /// @param params method parameters
    /// @return JSON-RPC notification string
    public String createNotification(String method, Object params) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("method", method);
        root.putPOJO("params", params);
        return root.toString();
    }

    /// Creates a JSON-RPC success response.
    ///
    /// @param id the request ID being responded to
    /// @param result the result data
    /// @return JSON-RPC response string
    public String createResponse(String id, Object result) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", id);
        root.putPOJO("result", result);
        return root.toString();
    }

    /// Creates a JSON-RPC error response.
    ///
    /// @param id the request ID being responded to
    /// @param code error code
    /// @param message error message
    /// @return JSON-RPC error response string
    public String createErrorResponse(String id, int code, String message) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", id);
        ObjectNode error = root.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return root.toString();
    }

    /// Extracts the ID from a JSON-RPC message.
    ///
    /// @param json the JSON-RPC message
    /// @return the ID, or null if not present
    public String extractId(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode idNode = node.get("id");
            return idNode != null && !idNode.isNull() ? idNode.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /// Extracts the method from a JSON-RPC message.
    ///
    /// @param json the JSON-RPC message
    /// @return the method name, or null if not present
    public String extractMethod(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode methodNode = node.get("method");
            return methodNode != null ? methodNode.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /// Parses a JSON-RPC response and extracts the result.
    ///
    /// @param json the JSON-RPC response
    /// @return the result as a Map, or error information
    /// @throws McpException if parsing fails or response contains error
    public Map<String, Object> parseResult(String json) throws McpException {
        try {
            JsonNode node = mapper.readTree(json);

            // Check for error
            JsonNode errorNode = node.get("error");
            if (errorNode != null && !errorNode.isNull()) {
                String message =
                        errorNode.has("message")
                                ? errorNode.get("message").asText()
                                : "Unknown error";
                int code = errorNode.has("code") ? errorNode.get("code").asInt() : -1;
                throw new McpException("JSON-RPC error " + code + ": " + message);
            }

            // Extract result
            JsonNode resultNode = node.get("result");
            if (resultNode == null || resultNode.isNull()) {
                return Map.of();
            }

            return mapper.convertValue(resultNode, Map.class);
        } catch (JsonProcessingException e) {
            throw new McpException("Failed to parse JSON-RPC response: " + e.getMessage(), e);
        }
    }

    /// Parses JSON-RPC params from a message.
    ///
    /// @param json the JSON-RPC message
    /// @return params as a Map
    public Map<String, Object> parseParams(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode paramsNode = node.get("params");
            if (paramsNode == null || paramsNode.isNull()) {
                return Map.of();
            }
            return mapper.convertValue(paramsNode, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    /// Checks if a message is a JSON-RPC response (has result or error, no method).
    ///
    /// @param json the JSON-RPC message
    /// @return true if this is a response
    public boolean isResponse(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return (node.has("result") || node.has("error")) && !node.has("method");
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /// Checks if a message is a JSON-RPC request or notification (has method).
    ///
    /// @param json the JSON-RPC message
    /// @return true if this is a request or notification
    public boolean isRequest(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return node.has("method");
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
