package io.hensu.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.util.Locale;

/// One HTTP answer from an MCP server, already reduced to the JSON-RPC message it carried.
///
/// @param status the HTTP status code
/// @param headers the response headers, not null
/// @param body the JSON-RPC message, or the empty string when there was none, not null
record HttpReply(int status, HttpHeaders headers, String body) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// The most of a body, in code points, that an error message quotes.
    static final int EXCERPT_LIMIT = 200;

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

    /// Returns what an error message may quote of the body.
    ///
    /// A gateway in front of the server answers a failure with its own error
    /// page, often kilobytes of HTML, and the message this lands in reaches both
    /// the operator and the agent. A JSON-RPC error contributes its message,
    /// markup contributes nothing, and anything else is quoted with its
    /// whitespace collapsed and cut at {@link #EXCERPT_LIMIT} code points.
    ///
    /// @return the excerpt, or the empty string when there is nothing worth quoting
    String excerpt() {
        JsonNode error = jsonRpcError();
        if (error != null) {
            return error.path("message").asText("");
        }
        String flat = body == null ? "" : body.strip().replaceAll("\\s+", " ");
        String type = headers.firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        if (flat.isEmpty() || type.contains("html") || flat.startsWith("<")) {
            return "";
        }
        if (flat.codePointCount(0, flat.length()) <= EXCERPT_LIMIT) {
            return flat;
        }
        return flat.substring(0, flat.offsetByCodePoints(0, EXCERPT_LIMIT)) + "…";
    }
}
