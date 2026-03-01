package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.hensu.core.plan.PlanStepAction;
import java.io.IOException;
import java.io.Serial;
import java.util.Map;

/// Deserializes the {@link PlanStepAction} sealed hierarchy using a {@code "type"}
/// discriminator field.
///
/// Handles two subtypes:
/// - **{@code "tool_call"}** — constructs {@link PlanStepAction.ToolCall} with {@code toolName}
///   and an optional {@code arguments} map (defaults to empty when absent).
/// - **{@code "synthesize"}** — constructs {@link PlanStepAction.Synthesize} with optional
///   {@code agentId} (may be {@code null} for unenriched steps stored before execution) and
///   {@code prompt}.
///
/// Both subtypes are constructed directly from {@link JsonNode} values — no POJO reflection.
/// Native-image safe without any {@code reflect-config.json} entries.
///
/// @implNote Package-private. Registered by {@link HensuJacksonModule}.
/// @see PlanStepActionSerializer for the inverse operation
class PlanStepActionDeserializer extends StdDeserializer<PlanStepAction> {

    @Serial private static final long serialVersionUID = -6309841527034912853L;

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    PlanStepActionDeserializer() {
        super(PlanStepAction.class);
    }

    /// Reads the {@code "type"} field from the token stream and dispatches to the appropriate
    /// {@link PlanStepAction} subtype constructor.
    ///
    /// @param p the JSON parser positioned at the start of the action object, not null
    /// @param ctx the deserialization context, not null
    /// @return the deserialized {@link PlanStepAction} instance, never null
    /// @throws IOException if the {@code "type"} value is unknown or a required field is absent
    @Override
    public PlanStepAction deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode root = mapper.readTree(p);

        String type = root.get("type").asText();

        return switch (type) {
            case "tool_call" -> {
                String toolName = root.get("toolName").asText();
                Map<String, Object> arguments =
                        root.has("arguments")
                                ? mapper.convertValue(root.get("arguments"), OBJECT_MAP)
                                : Map.of();
                yield new PlanStepAction.ToolCall(toolName, arguments);
            }
            case "synthesize" -> {
                String agentId =
                        root.has("agentId") && !root.get("agentId").isNull()
                                ? root.get("agentId").asText()
                                : null;
                String prompt = root.get("prompt").asText();
                yield new PlanStepAction.Synthesize(agentId, prompt);
            }
            default -> throw new IOException("Unknown PlanStepAction type: " + type);
        };
    }
}
