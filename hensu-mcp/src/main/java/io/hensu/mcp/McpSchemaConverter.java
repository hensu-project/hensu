package io.hensu.mcp;

import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolDefinition.ParameterDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Converts MCP tool descriptors into the engine's {@link ToolDefinition} model.
///
/// MCP describes a tool's inputs with JSON Schema, while the engine describes
/// them with a flat list of {@link ParameterDef}. The translation between the
/// two is identical for every runtime, so it lives here rather than beside any
/// one discovery mechanism: the server discovers tools over SSE and the CLI
/// discovers them over stdio, and both arrive at the same definitions.
///
/// ### Schema Coverage
/// Two things come out of one input schema, and neither replaces the other.
///
/// The {@link ParameterDef} list is a **lossy projection**: the property name,
/// its `type`, its `description`, its `default`, and whether the schema's
/// `required` array names it. Nothing richer survives it – an enumeration, an
/// array's item type, a composition keyword. It is kept because the engine reads
/// it: `ToolPreview` lists the parameters, the approval frame names them, and the
/// router's duplicate check walks them.
///
/// The **raw schema** is carried through verbatim on
/// {@link ToolDefinition#rawSchema()}, and that is what reaches the model. An
/// adapter hands it to the provider unchanged, so a tool whose `path` parameter
/// is `{"enum": ["a","b"]}` is offered to the agent as those two choices rather
/// than as an unconstrained string. Revision 2026-07-28 loosened `inputSchema` to
/// admit any JSON Schema 2020-12 keyword, which makes carrying the original the
/// only translation that can stay correct.
///
/// @see ToolDefinition for the engine-side model
/// @see McpConnection.McpToolDescriptor for the MCP-side model
public final class McpSchemaConverter {

    private McpSchemaConverter() {
        // Utility class
    }

    /// Converts an MCP tool descriptor to a Hensu tool definition.
    ///
    /// @param mcpTool the MCP tool descriptor, not null
    /// @return the converted tool definition, never null
    /// @throws NullPointerException if mcpTool is null
    public static ToolDefinition convert(McpConnection.McpToolDescriptor mcpTool) {
        Objects.requireNonNull(mcpTool, "mcpTool must not be null");
        List<ParameterDef> parameters = extractParameters(mcpTool.inputSchema());
        Map<String, Object> schema = mcpTool.inputSchema();
        return new ToolDefinition(
                mcpTool.name(),
                mcpTool.description(),
                parameters,
                null,
                schema == null || schema.isEmpty() ? null : schema);
    }

    /// Extracts parameter definitions from an MCP input schema.
    ///
    /// @param inputSchema the JSON Schema object describing the tool inputs, may be null
    /// @return the parameters, never null (empty when the schema declares none)
    static List<ParameterDef> extractParameters(Map<String, Object> inputSchema) {
        if (inputSchema == null) {
            return List.of();
        }

        if (!(inputSchema.get("properties") instanceof Map<?, ?> propsMap)) {
            return List.of();
        }

        List<String> required = extractRequiredList(inputSchema);

        List<ParameterDef> params = new ArrayList<>();
        for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
            if (!(entry.getKey() instanceof String paramName)
                    || !(entry.getValue() instanceof Map<?, ?> paramSchema)) {
                continue;
            }

            String type = getString(paramSchema, "type", "string");
            String description = getString(paramSchema, "description", "");
            boolean isRequired = required.contains(paramName);
            Object defaultValue = paramSchema.get("default");

            // MCP's schema has no notion of a secret parameter, so nothing discovered
            // here is marked sensitive; only locally declared tools can claim that.
            params.add(
                    new ParameterDef(
                            paramName, type, description, isRequired, defaultValue, false));
        }

        return List.copyOf(params);
    }

    private static List<String> extractRequiredList(Map<String, Object> schema) {
        if (schema.get("required") instanceof List<?> required) {
            return required.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    private static String getString(Map<?, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
