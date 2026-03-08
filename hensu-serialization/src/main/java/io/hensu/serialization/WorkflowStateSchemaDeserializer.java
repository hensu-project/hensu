package io.hensu.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.hensu.core.workflow.state.StateVariableDeclaration;
import io.hensu.core.workflow.state.VarType;
import io.hensu.core.workflow.state.WorkflowStateSchema;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

/// Custom deserializer for {@link WorkflowStateSchema}.
///
/// Extracts the `"variables"` array manually from the JSON tree, constructing each
/// {@link StateVariableDeclaration} by direct field extraction. This avoids all reflection
/// on both {@code WorkflowStateSchema} and {@code StateVariableDeclaration}, satisfying
/// the GraalVM native-image `treeToValue` rule from the serialization guide.
///
/// ### Why not `@JsonCreator` mixin?
/// {@code WorkflowStateSchema} uses a constructor-level `@JsonCreator` annotation. In native
/// image, Jackson resolves constructor annotations on the **mixin class** via
/// {@code getDeclaredConstructors()}, which requires explicit reflection registration of the
/// mixin class itself — not just the domain class. A custom deserializer avoids this
/// registration entirely.
///
/// @implNote GraalVM-safe. All fields are extracted via {@link JsonNode} accessors with no
/// {@code treeToValue} delegation.
/// @see HensuJacksonModule for registration
/// @see WorkflowStateSchema
class WorkflowStateSchemaDeserializer extends StdDeserializer<WorkflowStateSchema> {

    @Serial private static final long serialVersionUID = -6755541212843942046L;

    WorkflowStateSchemaDeserializer() {
        super(WorkflowStateSchema.class);
    }

    @Override
    public WorkflowStateSchema deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode root = mapper.readTree(p);

        List<StateVariableDeclaration> variables = new ArrayList<>();
        JsonNode variablesNode = root.get("variables");
        if (variablesNode != null && variablesNode.isArray()) {
            for (JsonNode v : variablesNode) {
                variables.add(
                        new StateVariableDeclaration(
                                v.get("name").asText(),
                                VarType.valueOf(v.get("type").asText()),
                                v.get("isInput").asBoolean()));
            }
        }
        return new WorkflowStateSchema(variables);
    }
}
