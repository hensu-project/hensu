package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.parallel.Branch;
import io.hensu.core.execution.parallel.ConsensusConfig;
import io.hensu.core.execution.parallel.ConsensusStrategy;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanningConfig;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.workflow.node.*;
import io.hensu.core.workflow.transition.TransitionRule;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Deserializes JSON to the appropriate `Node` subtype using the `"nodeType"` discriminator field.
///
/// ### Extraction strategy
///
/// Simple nested types — all fields are primitives, strings, or enums — are extracted manually
/// from the `JsonNode` tree to avoid POJO reflection:
/// - `ReviewConfig` (mode + two booleans)
/// - `ConsensusConfig` (judgeAgentId, strategy, threshold)
/// - `Branch` (id, agentId, prompt, rubricId, weight)
///
/// Complex types that contain `Duration` or deeply nested structures delegate to `treeToValue`
/// and require reflection registration in `NativeImageConfig` in `hensu-server`:
/// - `PlanningConfig` (contains `PlanConstraints` which contains `java.time.Duration`)
/// - `Plan` (contains `List<PlannedStep>` and `PlanConstraints`)
///
/// @implNote Package-private. Registered by {@link HensuJacksonModule}.
/// @see NodeSerializer for the inverse operation
class NodeDeserializer extends StdDeserializer<Node> {

    @Serial private static final long serialVersionUID = -4216640652578505546L;

    private static final TypeReference<List<TransitionRule>> TRANSITION_LIST =
            new TypeReference<>() {};
    private static final TypeReference<List<Action>> ACTION_LIST = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    NodeDeserializer() {
        super(Node.class);
    }

    /// Reads `"id"` and `"nodeType"` from the token stream and dispatches to the
    /// appropriate subtype builder.
    ///
    /// @param p the JSON parser positioned at the start of the node object, not null
    /// @param ctxt the deserialization context, not null
    /// @return the constructed `Node`, never null
    /// @throws IOException if a required field is absent or the `"nodeType"` value is unrecognized
    @Override
    public Node deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode root = mapper.readTree(p);

        String id = root.get("id").asText();
        NodeType nodeType = NodeType.valueOf(root.get("nodeType").asText());

        return switch (nodeType) {
            case STANDARD -> deserializeStandard(mapper, root, id);
            case END -> deserializeEnd(root, id);
            case ACTION -> deserializeAction(mapper, root, id);
            case GENERIC -> deserializeGeneric(mapper, root, id);
            case PARALLEL -> deserializeParallel(mapper, root, id);
            case FORK -> deserializeFork(mapper, root, id);
            case JOIN -> deserializeJoin(mapper, root, id);
            case SUB_WORKFLOW -> deserializeSubWorkflow(mapper, root, id);
            case LOOP -> new LoopNode(id);
        };
    }

    private StandardNode deserializeStandard(ObjectMapper mapper, JsonNode root, String id)
            throws IOException {
        StandardNode.Builder b =
                StandardNode.builder()
                        .id(id)
                        .agentId(textOrNull(root, "agentId"))
                        .prompt(textOrNull(root, "prompt"))
                        .rubricId(textOrNull(root, "rubricId"))
                        .transitionRules(
                                readValue(mapper, root, "transitionRules", TRANSITION_LIST))
                        .planFailureTarget(textOrNull(root, "planFailureTarget"));

        if (root.has("reviewConfig")) {
            JsonNode rc = root.get("reviewConfig");
            b.reviewConfig(
                    new ReviewConfig(
                            ReviewMode.valueOf(rc.get("mode").asText()),
                            rc.get("allowBacktrack").asBoolean(),
                            rc.get("allowEdit").asBoolean()));
        }
        if (root.has("outputParams")) {
            b.outputParams(readValue(mapper, root, "outputParams", STRING_LIST));
        }
        if (root.has("planningConfig")) {
            b.planningConfig(mapper.treeToValue(root.get("planningConfig"), PlanningConfig.class));
        }
        if (root.has("staticPlan")) {
            b.staticPlan(mapper.treeToValue(root.get("staticPlan"), Plan.class));
        }
        return b.build();
    }

    private EndNode deserializeEnd(JsonNode root, String id) {
        return EndNode.builder()
                .id(id)
                .status(ExitStatus.valueOf(root.get("status").asText()))
                .build();
    }

    private ActionNode deserializeAction(ObjectMapper mapper, JsonNode root, String id)
            throws IOException {
        return ActionNode.builder()
                .id(id)
                .actions(readValue(mapper, root, "actions", ACTION_LIST))
                .transitionRules(readValue(mapper, root, "transitionRules", TRANSITION_LIST))
                .build();
    }

    private GenericNode deserializeGeneric(ObjectMapper mapper, JsonNode root, String id)
            throws IOException {
        GenericNode.Builder b =
                GenericNode.builder()
                        .id(id)
                        .executorType(root.get("executorType").asText())
                        .transitionRules(
                                readValue(mapper, root, "transitionRules", TRANSITION_LIST))
                        .rubricId(textOrNull(root, "rubricId"));

        if (root.has("config")) {
            b.config(mapper.convertValue(root.get("config"), OBJECT_MAP));
        }
        return b.build();
    }

    private ParallelNode deserializeParallel(ObjectMapper mapper, JsonNode root, String id)
            throws IOException {
        ParallelNode.Builder b =
                ParallelNode.builder(id)
                        .branches(
                                root.has("branches")
                                        ? deserializeBranches(root.get("branches"))
                                        : List.of());

        if (root.has("consensusConfig")) {
            JsonNode cc = root.get("consensusConfig");
            b.consensus(
                    new ConsensusConfig(
                            cc.has("judgeAgentId") && !cc.get("judgeAgentId").isNull()
                                    ? cc.get("judgeAgentId").asText()
                                    : null,
                            ConsensusStrategy.valueOf(cc.get("strategy").asText()),
                            cc.has("threshold") && !cc.get("threshold").isNull()
                                    ? cc.get("threshold").doubleValue()
                                    : null));
        }
        if (root.has("transitionRules")) {
            b.transitionRules(readValue(mapper, root, "transitionRules", TRANSITION_LIST));
        }
        return b.build();
    }

    private ForkNode deserializeFork(ObjectMapper mapper, JsonNode root, String id)
            throws IOException {
        ForkNode.Builder b =
                ForkNode.builder(id).targets(readValue(mapper, root, "targets", STRING_LIST));

        if (root.has("targetConfigs")) {
            b.targetConfigs(mapper.convertValue(root.get("targetConfigs"), OBJECT_MAP));
        }
        if (root.has("transitionRules")) {
            b.transitionRules(readValue(mapper, root, "transitionRules", TRANSITION_LIST));
        }
        if (root.has("waitForAll")) {
            b.waitForAll(root.get("waitForAll").asBoolean());
        }
        return b.build();
    }

    private JoinNode deserializeJoin(ObjectMapper mapper, JsonNode root, String id)
            throws IOException {
        JoinNode.Builder b =
                JoinNode.builder(id)
                        .awaitTargets(readValue(mapper, root, "awaitTargets", STRING_LIST));

        if (root.has("mergeStrategy")) {
            b.mergeStrategy(MergeStrategy.valueOf(root.get("mergeStrategy").asText()));
        }
        if (root.has("outputField")) {
            b.outputField(root.get("outputField").asText());
        }
        if (root.has("timeoutMs")) {
            b.timeoutMs(root.get("timeoutMs").asLong());
        }
        if (root.has("failOnAnyError")) {
            b.failOnAnyError(root.get("failOnAnyError").asBoolean());
        }
        if (root.has("transitionRules")) {
            b.transitionRules(readValue(mapper, root, "transitionRules", TRANSITION_LIST));
        }
        return b.build();
    }

    private SubWorkflowNode deserializeSubWorkflow(ObjectMapper mapper, JsonNode root, String id)
            throws IOException {
        return SubWorkflowNode.builder()
                .id(id)
                .workflowId(root.get("workflowId").asText())
                .inputMapping(
                        root.has("inputMapping")
                                ? mapper.convertValue(root.get("inputMapping"), STRING_MAP)
                                : Map.of())
                .outputMapping(
                        root.has("outputMapping")
                                ? mapper.convertValue(root.get("outputMapping"), STRING_MAP)
                                : Map.of())
                .transitionRules(readValue(mapper, root, "transitionRules", TRANSITION_LIST))
                .build();
    }

    private List<Branch> deserializeBranches(JsonNode array) {
        List<Branch> branches = new ArrayList<>();
        for (JsonNode b : array) {
            branches.add(
                    new Branch(
                            b.get("id").asText(),
                            b.get("agentId").asText(),
                            b.has("prompt") && !b.get("prompt").isNull()
                                    ? b.get("prompt").asText()
                                    : null,
                            b.has("rubricId") && !b.get("rubricId").isNull()
                                    ? b.get("rubricId").asText()
                                    : null,
                            b.has("weight") ? b.get("weight").doubleValue() : 1.0));
        }
        return branches;
    }

    private String textOrNull(JsonNode root, String field) {
        return root.has(field) ? root.get(field).asText() : null;
    }

    private <T> T readValue(
            ObjectMapper mapper, JsonNode root, String field, TypeReference<T> typeRef)
            throws IOException {
        if (!root.has(field)) {
            return mapper.readValue("[]", typeRef);
        }
        return mapper.treeToValue(root.get(field), mapper.constructType(typeRef.getType()));
    }
}
