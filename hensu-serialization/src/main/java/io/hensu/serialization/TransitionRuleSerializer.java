package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.hensu.core.workflow.transition.*;
import java.io.IOException;
import java.io.Serial;

/// Serializes `TransitionRule` sealed interface variants with a `type` discriminator.
///
/// @see TransitionRuleDeserializer for the inverse operation
class TransitionRuleSerializer extends StdSerializer<TransitionRule> {

    @Serial private static final long serialVersionUID = -1056252072975542467L;

    TransitionRuleSerializer() {
        super(TransitionRule.class);
    }

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
                provider.defaultSerializeField("conditions", t.conditions(), gen);
            }
            case RubricFailTransition _ -> gen.writeStringField("type", "rubricFail");
        }

        gen.writeEndObject();
    }
}
