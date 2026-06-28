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

/// Deserializes the `TransitionRule` sealed hierarchy using a `"type"` discriminator field.
///
/// Handled types:
/// - **`"success"`** → `SuccessTransition(targetNode)`
/// - **`"failure"`** → `FailureTransition(targetNode)`
/// - **`"noConsensus"`** → `NoConsensusTransition(targetNode)`
/// - **`"bounded"`** → `BoundedTransition(inner, namespace, budget, otherwise)` — the `inner`
///   field is recursively deserialized via {@link #deserializeNode(JsonNode)}
/// - **`"always"`** → `AlwaysTransition()`
/// - **`"score"`** → `ScoreTransition` with a manually extracted `ScoreCondition` list
/// - **`"rubricFail"`** → `RubricFailTransition` with a no-op lambda; the original
///   predicate is not serializable and cannot be restored from JSON
/// - **`"approval"`** → `ApprovalTransition(expected, targetNode)`
///
/// All nested domain types (`ScoreCondition`, `DoubleRange`) are extracted manually from the
/// `JsonNode` tree to avoid POJO reflection, keeping this deserializer native-image safe.
///
/// @implNote Package-private. Registered by {@link HensuJacksonModule}.
/// @see TransitionRuleSerializer for the inverse operation
class TransitionRuleDeserializer extends StdDeserializer<TransitionRule> {

    @Serial private static final long serialVersionUID = 5888360675187668726L;

    TransitionRuleDeserializer() {
        super(TransitionRule.class);
    }

    /// Reads the `"type"` field and delegates to {@link #deserializeNode(JsonNode)}.
    ///
    /// @param p the JSON parser positioned at the start of the transition rule object, not null
    /// @param ctx the deserialization context, not null
    /// @return the deserialized `TransitionRule`, never null
    /// @throws IOException if the `"type"` value is unknown or a required field is absent
    @Override
    public TransitionRule deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode root = mapper.readTree(p);
        return deserializeNode(root);
    }

    /// Walks a `JsonNode` tree and constructs the matching `TransitionRule` subtype.
    ///
    /// Extracted from {@link #deserialize} so that `BoundedTransition`'s nested `inner` field
    /// can be deserialized recursively without re-entering the Jackson streaming pipeline.
    ///
    /// @param root the JSON object node containing a `"type"` discriminator, not null
    /// @return the deserialized `TransitionRule`, never null
    /// @throws IOException if the `"type"` value is unknown or a required field is absent
    private TransitionRule deserializeNode(JsonNode root) throws IOException {
        String type = root.get("type").asText();
        boolean feedback = boolField(root, "withFeedback");

        return switch (type) {
            case "success" -> new SuccessTransition(root.get("targetNode").asText(), feedback);
            case "failure" -> {
                JsonNode tn = root.get("targetNode");
                String target = tn == null || tn.isNull() ? null : tn.asText();
                yield new FailureTransition(target);
            }
            case "noConsensus" ->
                    new NoConsensusTransition(root.get("targetNode").asText(), feedback);
            case "bounded" ->
                    new BoundedTransition(
                            deserializeNode(root.get("inner")),
                            root.get("namespace").asText(),
                            root.get("budget").asInt(),
                            root.get("otherwise").asText(),
                            boolField(root, "escalationWithFeedback"));
            case "always" -> new AlwaysTransition(feedback);
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
                yield new ScoreTransition(conditions, feedback);
            }
            case "rubricFail" -> new RubricFailTransition(_ -> null, feedback);
            case "approval" ->
                    new ApprovalTransition(
                            root.get("expected").asBoolean(),
                            root.get("targetNode").asText(),
                            feedback);
            default -> throw new IOException("Unknown TransitionRule type: " + type);
        };
    }

    private static boolean boolField(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return f != null && f.asBoolean(false);
    }
}
