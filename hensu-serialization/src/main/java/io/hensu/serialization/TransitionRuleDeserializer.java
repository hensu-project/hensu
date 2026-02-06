package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.workflow.transition.*;
import java.io.IOException;
import java.io.Serial;
import java.util.List;

/// Deserializes `TransitionRule` variants based on the `type` discriminator field.
///
/// @see TransitionRuleSerializer for the inverse operation
class TransitionRuleDeserializer extends StdDeserializer<TransitionRule> {

    @Serial private static final long serialVersionUID = 5888360675187668726L;

    TransitionRuleDeserializer() {
        super(TransitionRule.class);
    }

    @Override
    public TransitionRule deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode root = mapper.readTree(p);

        String type = root.get("type").asText();

        return switch (type) {
            case "success" -> new SuccessTransition(root.get("targetNode").asText());
            case "failure" ->
                    new FailureTransition(
                            root.get("retryCount").asInt(), root.get("targetNode").asText());
            case "always" -> new AlwaysTransition();
            case "score" ->
                    new ScoreTransition(
                            mapper.treeToValue(
                                    root.get("conditions"),
                                    mapper.constructType(
                                            new TypeReference<
                                                    List<ScoreCondition>>() {}.getType())));
            case "rubricFail" -> new RubricFailTransition(_ -> null);
            default -> throw new IOException("Unknown TransitionRule type: " + type);
        };
    }
}
