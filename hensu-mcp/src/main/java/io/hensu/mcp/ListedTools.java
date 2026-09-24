package io.hensu.mcp;

import io.hensu.mcp.McpConnection.McpToolDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// One `tools/list` result, read into the tools to offer and the headers each one mirrors.
///
/// A connection swaps a whole listing in at once, so the catalog it offers and
/// the designations it mirrors from always come from the same answer.
///
/// @param descriptors the tools to offer agents, in published order, not null
/// @param designations each published tool's `x-mcp-header` reading, keyed by
///     name, not null; empty when the era mirrors no parameters
/// @param rejections one operator-facing reason per tool left out, not null
/// @implNote **Immutable.** Safe to share across threads.
record ListedTools(
        List<McpToolDescriptor> descriptors,
        Map<String, McpParameterHeaders.Designation> designations,
        List<String> rejections) {

    /// The listing before any `tools/list` was read.
    static final ListedTools NONE = new ListedTools(List.of(), Map.of(), List.of());

    /// Reads a `tools/list` result, leaving out tools whose header annotations
    /// are invalid when the era mirrors parameters.
    ///
    /// @param serverName the declared server name, for rejection reasons, not null
    /// @param result the JSON-RPC result, not null
    /// @param mirrors whether the settled era mirrors `x-mcp-header` parameters
    /// @return the listing, never null
    static ListedTools read(String serverName, Map<String, Object> result, boolean mirrors) {
        if (!(result.get("tools") instanceof List<?> published)) {
            return NONE;
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        Map<String, McpParameterHeaders.Designation> read = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        for (Object entry : published) {
            if (!(entry instanceof Map<?, ?> tool && tool.get("name") instanceof String name)) {
                continue;
            }
            Map<String, Object> schema =
                    tool.get("inputSchema") instanceof Map<?, ?> declared
                            ? castMap(declared)
                            : Map.of();
            if (mirrors) {
                McpParameterHeaders.Designation designation = McpParameterHeaders.read(schema);
                read.put(name, designation);
                if (designation instanceof McpParameterHeaders.Rejected rejected) {
                    rejections.add(rejectionNotice(serverName, name, rejected));
                    continue;
                }
            }
            descriptors.add(
                    new McpToolDescriptor(
                            name,
                            tool.get("description") instanceof String description
                                    ? description
                                    : "",
                            schema));
        }
        return new ListedTools(List.copyOf(descriptors), Map.copyOf(read), List.copyOf(rejections));
    }

    /// Derives a `tools/call`'s `Mcp-Param-*` headers from the same arguments the
    /// body carries, so the two cannot disagree.
    ///
    /// @param toolName the tool being called, not null
    /// @param params the `tools/call` params, not null
    /// @return the headers to mirror, never null (empty when the tool mirrors none)
    /// @throws McpInvalidArgumentException if a mirrored argument has no header form
    Map<String, String> mirroredHeaders(String toolName, Map<String, Object> params) {
        if (!(designations.get(toolName) instanceof McpParameterHeaders.Accepted accepted)
                || !(params.get("arguments") instanceof Map<?, ?> arguments)) {
            return Map.of();
        }
        return McpParameterHeaders.headers(accepted.mirrors(), castMap(arguments));
    }

    /// Explains why a tool is not offered.
    ///
    /// @param serverName the declared server name, not null
    /// @param toolName the tool left out, not null
    /// @param rejected the reason its annotations were refused, not null
    /// @return the operator-facing sentence, never null
    static String rejectionNotice(
            String serverName, String toolName, McpParameterHeaders.Rejected rejected) {
        return "MCP server '"
                + serverName
                + "' publishes '"
                + toolName
                + "' with an invalid x-mcp-header annotation, so it is not offered to agents: "
                + rejected.reason();
    }

    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
