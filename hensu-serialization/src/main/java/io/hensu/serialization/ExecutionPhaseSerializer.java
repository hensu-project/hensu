package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.hensu.core.state.ExecutionPhase;
import java.io.IOException;
import java.io.Serial;

/// Serializes the {@link ExecutionPhase} sealed hierarchy with a {@code "type"} discriminator.
///
/// Emitted JSON shape per subtype:
/// - **{@link ExecutionPhase.Initial}**: {@code {"type":"initial"}}
/// - **{@link ExecutionPhase.Awaiting}**: {@code {"type":"awaiting_post_processor",
///
///
/// "nodeId":"...","processorId":"...","cachedResult":{...},"correlationId":"...",
/// "requestedAt":"..."}}
/// - **{@link ExecutionPhase.Terminal}**: {@code {"type":"terminal"}}
///
/// @implNote Package-private. Registered by {@link HensuJacksonModule}.
/// @see ExecutionPhaseDeserializer for the inverse operation
class ExecutionPhaseSerializer extends StdSerializer<ExecutionPhase> {

    @Serial private static final long serialVersionUID = 1L;

    ExecutionPhaseSerializer() {
        super(ExecutionPhase.class);
    }

    @Override
    public void serialize(ExecutionPhase phase, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeStartObject();

        switch (phase) {
            case ExecutionPhase.Initial _ -> gen.writeStringField("type", "initial");
            case ExecutionPhase.Awaiting a -> {
                gen.writeStringField("type", "awaiting_post_processor");
                gen.writeStringField("nodeId", a.nodeId());
                gen.writeStringField("processorId", a.processorId());
                provider.defaultSerializeField("cachedResult", a.cachedResult(), gen);
                gen.writeStringField("correlationId", a.correlationId());
                provider.defaultSerializeField("requestedAt", a.requestedAt(), gen);
            }
            case ExecutionPhase.Terminal _ -> gen.writeStringField("type", "terminal");
        }

        gen.writeEndObject();
    }
}
