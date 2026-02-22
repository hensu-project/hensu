package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.hensu.core.workflow.node.*;
import java.io.IOException;
import java.io.Serial;

/// Serializes all `Node` subtypes to JSON with a `"nodeType"` discriminator field.
///
/// Every serialized object begins with `"id"` and `"nodeType"`, followed by subtype-specific
/// fields. Optional fields (e.g., `agentId`, `prompt`) are omitted when null or empty.
///
/// ```
/// NodeType       Additional fields
/// ———————————————+————————————————————————————————————————————————————————————————————————
/// STANDARD       │ agentId, prompt, rubricId, reviewConfig, transitionRules,
///                │ outputParams, planningConfig, staticPlan, planFailureTarget
/// END            │ status
/// ACTION         │ actions, transitionRules
/// GENERIC        │ executorType, config, transitionRules, rubricId
/// PARALLEL       │ branches, consensusConfig, transitionRules
/// FORK           │ targets, targetConfigs, transitionRules, waitForAll
/// JOIN           │ awaitTargets, mergeStrategy, outputField, timeoutMs,
///                │ failOnAnyError, transitionRules
/// SUB_WORKFLOW   │ workflowId, inputMapping, outputMapping, transitionRules
/// LOOP           │ (none — only id and nodeType are written)
/// ```
///
/// @implNote Package-private. Registered by {@link HensuJacksonModule}.
/// @see NodeDeserializer for the inverse operation
class NodeSerializer extends StdSerializer<Node> {

    @Serial private static final long serialVersionUID = -2812534753587810126L;

    NodeSerializer() {
        super(Node.class);
    }

    /// Writes a `Node` object to JSON, dispatching on the concrete subtype.
    ///
    /// @param node the node to serialize, not null
    /// @param gen the JSON generator, not null
    /// @param provider the serializer provider, not null
    /// @throws IOException if the node subtype is unrecognized or a write error occurs
    @Override
    public void serialize(Node node, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeStartObject();
        gen.writeStringField("id", node.getId());
        gen.writeStringField("nodeType", node.getNodeType().name());

        switch (node) {
            case StandardNode n -> writeStandardNode(n, gen, provider);
            case EndNode n -> writeEndNode(n, gen);
            case ActionNode n -> writeActionNode(n, gen, provider);
            case GenericNode n -> writeGenericNode(n, gen, provider);
            case ParallelNode n -> writeParallelNode(n, gen, provider);
            case ForkNode n -> writeForkNode(n, gen, provider);
            case JoinNode n -> writeJoinNode(n, gen, provider);
            case SubWorkflowNode n -> writeSubWorkflowNode(n, gen, provider);
            case LoopNode _ -> {} // Stub — id and nodeType already written
            default ->
                    throw new IOException("Unknown node type: " + node.getClass().getSimpleName());
        }

        gen.writeEndObject();
    }

    private void writeStandardNode(StandardNode n, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        writeIfNotNull(gen, "agentId", n.getAgentId());
        writeIfNotNull(gen, "prompt", n.getPrompt());
        writeIfNotNull(gen, "rubricId", n.getRubricId());
        if (n.getReviewConfig() != null) {
            gen.writeObjectField("reviewConfig", n.getReviewConfig());
        }
        provider.defaultSerializeField("transitionRules", n.getTransitionRules(), gen);
        if (!n.getOutputParams().isEmpty()) {
            provider.defaultSerializeField("outputParams", n.getOutputParams(), gen);
        }
        if (n.getPlanningConfig().isEnabled()) {
            gen.writeObjectField("planningConfig", n.getPlanningConfig());
        }
        if (n.getStaticPlan() != null) {
            gen.writeObjectField("staticPlan", n.getStaticPlan());
        }
        writeIfNotNull(gen, "planFailureTarget", n.getPlanFailureTarget());
    }

    private void writeEndNode(EndNode n, JsonGenerator gen) throws IOException {
        gen.writeStringField("status", n.getStatus().name());
    }

    private void writeActionNode(ActionNode n, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        provider.defaultSerializeField("actions", n.getActions(), gen);
        provider.defaultSerializeField("transitionRules", n.getTransitionRules(), gen);
    }

    private void writeGenericNode(GenericNode n, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeStringField("executorType", n.getExecutorType());
        if (!n.getConfig().isEmpty()) {
            provider.defaultSerializeField("config", n.getConfig(), gen);
        }
        provider.defaultSerializeField("transitionRules", n.getTransitionRules(), gen);
        writeIfNotNull(gen, "rubricId", n.getRubricId());
    }

    private void writeParallelNode(ParallelNode n, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        provider.defaultSerializeField("branches", n.getBranchesList(), gen);
        if (n.getConsensusConfig() != null) {
            gen.writeObjectField("consensusConfig", n.getConsensusConfig());
        }
        if (!n.getTransitionRules().isEmpty()) {
            provider.defaultSerializeField("transitionRules", n.getTransitionRules(), gen);
        }
    }

    private void writeForkNode(ForkNode n, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        provider.defaultSerializeField("targets", n.getTargets(), gen);
        if (!n.getTargetConfigs().isEmpty()) {
            provider.defaultSerializeField("targetConfigs", n.getTargetConfigs(), gen);
        }
        if (!n.getTransitionRules().isEmpty()) {
            provider.defaultSerializeField("transitionRules", n.getTransitionRules(), gen);
        }
        if (n.isWaitForAll()) {
            gen.writeBooleanField("waitForAll", true);
        }
    }

    private void writeJoinNode(JoinNode n, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        provider.defaultSerializeField("awaitTargets", n.getAwaitTargets(), gen);
        gen.writeStringField("mergeStrategy", n.getMergeStrategy().name());
        gen.writeStringField("outputField", n.getOutputField());
        gen.writeNumberField("timeoutMs", n.getTimeoutMs());
        gen.writeBooleanField("failOnAnyError", n.isFailOnAnyError());
        if (!n.getTransitionRules().isEmpty()) {
            provider.defaultSerializeField("transitionRules", n.getTransitionRules(), gen);
        }
    }

    private void writeSubWorkflowNode(
            SubWorkflowNode n, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStringField("workflowId", n.getWorkflowId());
        provider.defaultSerializeField("inputMapping", n.getInputMapping(), gen);
        provider.defaultSerializeField("outputMapping", n.getOutputMapping(), gen);
        provider.defaultSerializeField("transitionRules", n.getTransitionRules(), gen);
    }

    private void writeIfNotNull(JsonGenerator gen, String field, String value) throws IOException {
        if (value != null && !value.isEmpty()) {
            gen.writeStringField(field, value);
        }
    }
}
