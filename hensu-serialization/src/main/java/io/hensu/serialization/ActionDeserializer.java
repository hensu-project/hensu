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

/// Deserializes `Action` variants based on the `type` discriminator field.
///
/// @see ActionSerializer for the inverse operation
class ActionDeserializer extends StdDeserializer<Action> {

    @Serial private static final long serialVersionUID = -2982494722970920876L;

    ActionDeserializer() {
        super(Action.class);
    }

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
