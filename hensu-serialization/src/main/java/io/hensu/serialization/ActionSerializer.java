package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.hensu.core.execution.action.Action;
import java.io.IOException;
import java.io.Serial;

/// Serializes `Action` sealed class variants with a `type` discriminator.
///
/// @see ActionDeserializer for the inverse operation
class ActionSerializer extends StdSerializer<Action> {

    @Serial private static final long serialVersionUID = -4171718242621821911L;

    ActionSerializer() {
        super(Action.class);
    }

    @Override
    public void serialize(Action action, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeStartObject();

        switch (action) {
            case Action.Send s -> {
                gen.writeStringField("type", "send");
                gen.writeStringField("handlerId", s.getHandlerId());
                if (!s.getPayload().isEmpty()) {
                    provider.defaultSerializeField("payload", s.getPayload(), gen);
                }
            }
            case Action.Execute e -> {
                gen.writeStringField("type", "execute");
                gen.writeStringField("commandId", e.getCommandId());
            }
        }

        gen.writeEndObject();
    }
}
