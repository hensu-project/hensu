package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.hensu.core.execution.action.Action;
import java.io.IOException;
import java.io.Serial;
import java.util.Map;

/// Deserializes the `Action` sealed hierarchy using a `"type"` discriminator field.
///
/// Handles two subtypes:
/// - **`"send"`** — constructs `Action.Send` with `handlerId` and an optional `payload` map.
///   `payload` is extracted via `convertValue` — no POJO reflection required.
/// - **`"execute"`** — constructs `Action.Execute` with `commandId`.
///
/// Both subtypes are constructed directly from `JsonNode` values, making this deserializer
/// native-image safe without any `reflect-config.json` entries.
///
/// @implNote Package-private. Registered by {@link HensuJacksonModule}.
/// @see ActionSerializer for the inverse operation
class ActionDeserializer extends StdDeserializer<Action> {

    @Serial private static final long serialVersionUID = -2982494722970920876L;

    ActionDeserializer() {
        super(Action.class);
    }

    /// Reads the `"type"` field from the token stream and dispatches to the appropriate
    /// `Action` subtype constructor.
    ///
    /// @param p the JSON parser positioned at the start of the action object, not null
    /// @param ctx the deserialization context, not null
    /// @return the deserialized `Action` instance, never null
    /// @throws IOException if the `"type"` value is unknown or a required field is absent
    @Override
    public Action deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode root = mapper.readTree(p);

        String type = root.get("type").asText();

        return switch (type) {
            case "send" -> {
                String handlerId = root.get("handlerId").asText();
                Map<String, Object> payload =
                        root.has("payload")
                                ? mapper.convertValue(root.get("payload"), new TypeReference<>() {})
                                : Map.of();
                yield new Action.Send(handlerId, payload);
            }
            case "execute" -> new Action.Execute(root.get("commandId").asText());
            default -> throw new IOException("Unknown Action type: " + type);
        };
    }
}
