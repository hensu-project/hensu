package io.hensu.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/// Reads the one JSON-RPC message a request is waiting for out of an SSE body.
///
/// Frames are `data:` lines accumulated until a blank line. Comment lines
/// beginning with `:` and the `event:` and `id:` fields carry nothing this
/// client needs. Notifications and messages correlated to another request are
/// skipped. A result whose `resultType` is absent is `"complete"`, which the
/// spec requires of a client talking to an earlier-protocol server.
final class SseFrames {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonRpc JSON_RPC = new JsonRpc(MAPPER);

    private SseFrames() {}

    /// Reads frames until the complete answer to `requestId` arrives.
    ///
    /// @param body the SSE response body, not null; closed on return
    /// @param requestId the id of the request being answered, not null
    /// @param serverName the declared server name, for failure messages, not null
    /// @return the answer, or the empty string when the stream ended before any
    ///     message for `requestId` arrived – the caller has a status code and can
    ///     say something better than "stream ended"
    /// @throws McpException if an answer began and never completed, or the
    ///     stream failed while being read
    static String read(InputStream body, String requestId, String serverName) {
        StringBuilder data = new StringBuilder();
        String candidate = "";
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    String message = data.toString();
                    data.setLength(0);
                    if (message.isBlank()) {
                        continue;
                    }
                    if (!matchesRequest(message, requestId)) {
                        continue;
                    }
                    candidate = message;
                    if (isComplete(message)) {
                        return candidate;
                    }
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    String value = line.substring("data:".length());
                    // The SSE grammar removes exactly one leading space, not all
                    // leading whitespace.
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                }
            }
        } catch (IOException e) {
            throw truncated(serverName, candidate, e);
        }
        // Falling out of the loop means end of stream. A candidate that arrived
        // but never said `resultType: complete` means the server began an answer
        // and did not finish it, and returning the fragment would report a
        // truncated call as a successful one.
        if (!candidate.isEmpty()) {
            throw truncated(serverName, candidate, null);
        }
        return candidate;
    }

    /// Fails a call whose answer began and never completed.
    private static McpException truncated(String serverName, String candidate, IOException cause) {
        String message =
                "MCP server '"
                        + serverName
                        + "' closed its response stream before the result was complete";
        if (candidate.isEmpty() && cause != null) {
            return new McpException(
                    "MCP server '" + serverName + "' closed its response stream early", cause);
        }
        return cause == null ? new McpException(message) : new McpException(message, cause);
    }

    private static boolean matchesRequest(String message, String requestId) {
        String id = JSON_RPC.extractId(message);
        return id != null && id.equals(requestId);
    }

    private static boolean isComplete(String message) {
        try {
            JsonNode resultType = MAPPER.readTree(message).path("result").path("resultType");
            return !resultType.isTextual() || "complete".equals(resultType.asText());
        } catch (IOException e) {
            return true;
        }
    }
}
