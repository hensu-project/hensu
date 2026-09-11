package io.hensu.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/// Renders an MCP `tools/call` response into the text an agent reads.
///
/// The MCP wire result is a map whose `content` entry holds an ordered array of
/// typed blocks. Handing that map to the model directly would put a Java
/// `Map.toString` – braces, equals signs, identity hashes – into the context
/// window, so this renderer flattens it into prose the model can act on and
/// maps the protocol's `isError` flag onto the engine's outcome vocabulary.
///
/// ### Rendering Rules
/// - `text` blocks contribute their text, in array order, one per line.
/// - Every other block type renders as a short typed placeholder naming the
///   block and whatever locates it – a URI, or a media type – because the
///   bytes themselves are of no use inside a text completion.
/// - A payload that is not shaped like a content-block list falls back to
///   canonical JSON, which at least stays machine-readable.
///
/// ### Runtime Neutrality
/// Both runtimes render the same way: the server calls tools over SSE and the
/// CLI calls them over stdio, but an agent must not be able to tell which
/// transport produced a result.
///
/// @see ToolCallResult for the result this feeds
public final class McpResultRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONTENT_KEY = "content";
    private static final String IS_ERROR_KEY = "isError";
    private static final String TYPE_KEY = "type";
    private static final String TEXT_TYPE = "text";

    private McpResultRenderer() {
        // Utility class
    }

    /// Converts an MCP `tools/call` response into a tool call result.
    ///
    /// A response carrying `isError: true` becomes {@link ToolCallStatus#FAILURE}
    /// with the rendered text as the error message, so the agent sees what went
    /// wrong rather than an empty failure.
    ///
    /// @param toolName the tool that was invoked, not null
    /// @param response the parsed MCP response, may be null
    /// @return the result, never null
    public static ToolCallResult toResult(String toolName, Map<String, Object> response) {
        String rendered = render(response);
        return isError(response)
                ? ToolCallResult.of(toolName, ToolCallStatus.FAILURE, null, rendered, null)
                : ToolCallResult.success(toolName, rendered);
    }

    /// Renders the response content as agent-readable text.
    ///
    /// @param response the parsed MCP response, may be null
    /// @return the rendered text, never null (empty when there is no content)
    public static String render(Map<String, Object> response) {
        if (response == null || response.isEmpty()) {
            return "";
        }

        Object content = response.get(CONTENT_KEY);
        if (!(content instanceof List<?> blocks)) {
            return toJson(response);
        }

        StringJoiner joiner = new StringJoiner("\n");
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> blockMap) {
                joiner.add(renderBlock(blockMap));
            } else {
                joiner.add(String.valueOf(block));
            }
        }
        return joiner.toString();
    }

    /// Returns whether the response reports a tool-side error.
    ///
    /// @param response the parsed MCP response, may be null
    /// @return true if the response sets `isError` to true
    public static boolean isError(Map<String, Object> response) {
        return response != null && Boolean.TRUE.equals(response.get(IS_ERROR_KEY));
    }

    private static String renderBlock(Map<?, ?> block) {
        String type = asString(block.get(TYPE_KEY));

        if (TEXT_TYPE.equals(type)) {
            return asString(block.get(TEXT_TYPE));
        }

        // Non-text blocks carry bytes or references that a text completion cannot
        // consume. Name the block and whatever locates it, and drop the payload.
        String label = type.isEmpty() ? "unknown" : type;
        String locator = locate(block);
        return locator.isEmpty() ? "[" + label + "]" : "[" + label + ": " + locator + "]";
    }

    private static String locate(Map<?, ?> block) {
        String uri = asString(block.get("uri"));
        String mimeType = asString(block.get("mimeType"));

        if (block.get("resource") instanceof Map<?, ?> resource) {
            uri = uri.isEmpty() ? asString(resource.get("uri")) : uri;
            mimeType = mimeType.isEmpty() ? asString(resource.get("mimeType")) : mimeType;
        }

        if (!uri.isEmpty() && !mimeType.isEmpty()) {
            return uri + " (" + mimeType + ")";
        }
        return uri.isEmpty() ? mimeType : uri;
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : "";
    }

    private static String toJson(Map<String, Object> response) {
        try {
            return MAPPER.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            // Unreachable for a JSON-derived map, but a rendered result must never
            // be the reason a tool call fails.
            return String.valueOf(response);
        }
    }
}
