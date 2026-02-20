package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.hensu.core.rubric.model.ComparisonOperator;
import io.hensu.core.rubric.model.DoubleRange;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.workflow.transition.*;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

/// Deserializes `TransitionRule` variants based on the `type` discriminator field.
///
/// All nested domain types (`ScoreCondition`, `DoubleRange`) are extracted manually from the
/// `JsonNode` tree to avoid POJO reflection, keeping this deserializer native-image safe.
///
/// @see TransitionRuleSerializer for the inverse operation
class TransitionRuleDeserializer extends StdDeserializer<TransitionRule> {

    @Serial private static final long serialVersionUID = 5888360675187668726L;

    TransitionRuleDeserializer() {
        super(TransitionRule.class);
    }

    @Override
    public TransitionRule deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode root = mapper.readTree(p);

        String type = root.get("type").asText();

        return switch (type) {
            case "success" -> new SuccessTransition(root.get("targetNode").asText());
            case "failure" ->
                    new FailureTransition(
                            root.get("retryCount").asInt(), root.get("targetNode").asText());
            case "always" -> new AlwaysTransition();
            case "score" -> {
                List<ScoreCondition> conditions = new ArrayList<>();
                for (JsonNode c : root.get("conditions")) {
                    ComparisonOperator op = ComparisonOperator.valueOf(c.get("operator").asText());
                    Double value =
                            c.has("value") && !c.get("value").isNull()
                                    ? c.get("value").doubleValue()
                                    : null;
                    DoubleRange range = null;
                    if (c.has("range") && !c.get("range").isNull()) {
                        JsonNode r = c.get("range");
                        range =
                                new DoubleRange(
                                        r.get("start").doubleValue(), r.get("end").doubleValue());
                    }
                    conditions.add(
                            new ScoreCondition(op, value, range, c.get("targetNode").asText()));
                }
                yield new ScoreTransition(conditions);
            }
            case "rubricFail" -> new RubricFailTransition(_ -> null);
            default -> throw new IOException("Unknown TransitionRule type: " + type);
        };
    }
}
