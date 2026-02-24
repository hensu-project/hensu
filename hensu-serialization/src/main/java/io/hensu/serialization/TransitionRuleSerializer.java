package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.hensu.core.rubric.model.DoubleRange;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.workflow.transition.*;
import java.io.IOException;
import java.io.Serial;

/// Serializes the `TransitionRule` sealed hierarchy with a `"type"` discriminator field.
///
/// Emitted JSON shape per subtype:
/// - **`SuccessTransition`**: `{"type":"success","targetNode":"..."}`
/// - **`FailureTransition`**: `{"type":"failure","retryCount":N,"targetNode":"..."}`
/// - **`AlwaysTransition`**: `{"type":"always"}`
/// - **`ScoreTransition`**: `{"type":"score","conditions":[...]}` — each condition written
///   manually: `operator` (string), `value` (number|null), `range` (object|null), `targetNode`
/// - **`RubricFailTransition`**: `{"type":"rubricFail"}` — the handler predicate is not
///   serializable and is reconstructed as a no-op lambda on deserialization
///
/// @implNote Package-private. Registered by {@link HensuJacksonModule}.
/// @see TransitionRuleDeserializer for the inverse operation
class TransitionRuleSerializer extends StdSerializer<TransitionRule> {

    @Serial private static final long serialVersionUID = -1056252072975542467L;

    TransitionRuleSerializer() {
        super(TransitionRule.class);
    }

    /// Writes a `TransitionRule` to JSON, selecting fields by the concrete subtype.
    ///
    /// @param rule the transition rule to serialize, not null
    /// @param gen the JSON generator, not null
    /// @param provider the serializer provider, not null
    /// @throws IOException if the generator encounters a write error
    @Override
    public void serialize(TransitionRule rule, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeStartObject();

        switch (rule) {
            case SuccessTransition t -> {
                gen.writeStringField("type", "success");
                gen.writeStringField("targetNode", t.targetNode());
            }
            case FailureTransition t -> {
                gen.writeStringField("type", "failure");
                gen.writeNumberField("retryCount", t.retryCount());
                gen.writeStringField("targetNode", t.targetNode());
            }
            case AlwaysTransition _ -> gen.writeStringField("type", "always");
            case ScoreTransition t -> {
                gen.writeStringField("type", "score");
                gen.writeArrayFieldStart("conditions");
                for (ScoreCondition c : t.conditions()) {
                    gen.writeStartObject();
                    gen.writeStringField("operator", c.operator().name());
                    if (c.value() != null) {
                        gen.writeNumberField("value", c.value());
                    } else {
                        gen.writeNullField("value");
                    }
                    DoubleRange range = c.range();
                    if (range != null) {
                        gen.writeObjectFieldStart("range");
                        gen.writeNumberField("start", range.start());
                        gen.writeNumberField("end", range.end());
                        gen.writeEndObject();
                    } else {
                        gen.writeNullField("range");
                    }
                    gen.writeStringField("targetNode", c.targetNode());
                    gen.writeEndObject();
                }
                gen.writeEndArray();
            }
            case RubricFailTransition _ -> gen.writeStringField("type", "rubricFail");
        }

        gen.writeEndObject();
    }
}
