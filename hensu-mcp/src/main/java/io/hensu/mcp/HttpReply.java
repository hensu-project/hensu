package io.hensu.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpHeaders;

/// One HTTP answer from an MCP server, already reduced to the JSON-RPC message it carried.
///
/// @param status the HTTP status code
/// @param headers the response headers, not null
/// @param body the JSON-RPC message, or the empty string when there was none, not null
record HttpReply(int status, HttpHeaders headers, String body) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// Returns the first value of a response header.
    ///
    /// @param name the header name, case-insensitive, not null
    /// @return the value, or null when the header is absent
    String header(String name) {
        return headers.firstValue(name).orElse(null);
    }

    /// Returns the JSON-RPC `error` object the body carries.
    ///
    /// @return the error, or null when the body is not a JSON-RPC error with a code
    JsonNode jsonRpcError() {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode error = MAPPER.readTree(body).path("error");
            return error.isObject() && error.has("code") ? error : null;
        } catch (IOException e) {
            return null;
        }
    }
}
