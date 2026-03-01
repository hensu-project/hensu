package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.hensu.core.plan.PlanStepAction;
import java.io.IOException;
import java.io.Serial;

/// Serializes the {@link PlanStepAction} sealed hierarchy with a {@code "type"}
/// discriminator field.
///
/// Emitted JSON shape per subtype:
/// - **{@link PlanStepAction.ToolCall}**:
///   {@code {"type":"tool_call","toolName":"...","arguments":{...}}}.
///   The {@code arguments} object is omitted when empty.
/// - **{@link PlanStepAction.Synthesize}**:
///   {@code {"type":"synthesize","agentId":"...","prompt":"..."}}.
///   {@code agentId} is omitted when {@code null} (unenriched synthesize steps).
///
/// @implNote Package-private. Registered by {@link HensuJacksonModule}.
/// @see PlanStepActionDeserializer for the inverse operation
class PlanStepActionSerializer extends StdSerializer<PlanStepAction> {

    @Serial private static final long serialVersionUID = 5127983461287340211L;

    PlanStepActionSerializer() {
        super(PlanStepAction.class);
    }

    /// Writes the action to JSON, selecting fields by the concrete {@link PlanStepAction} subtype.
    ///
    /// @param action the action to serialize, not null
    /// @param gen the JSON generator, not null
    /// @param provider the serializer provider, not null
    /// @throws IOException if the generator encounters a write error
    @Override
    public void serialize(PlanStepAction action, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeStartObject();

        switch (action) {
            case PlanStepAction.ToolCall tc -> {
                gen.writeStringField("type", "tool_call");
                gen.writeStringField("toolName", tc.toolName());
                if (!tc.arguments().isEmpty()) {
                    provider.defaultSerializeField("arguments", tc.arguments(), gen);
                }
            }
            case PlanStepAction.Synthesize s -> {
                gen.writeStringField("type", "synthesize");
                if (s.agentId() != null) {
                    gen.writeStringField("agentId", s.agentId());
                }
                gen.writeStringField("prompt", s.prompt());
            }
        }

        gen.writeEndObject();
    }
}
