package io.hensu.core.workflow.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.SubWorkflowNode;
import io.hensu.core.workflow.state.StateVariableDeclaration;
import io.hensu.core.workflow.state.VarType;
import io.hensu.core.workflow.state.WorkflowStateSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Schema-consistency tests for the {@code SubWorkflowNode} branch of
/// {@link WorkflowValidator#validate(Workflow)}.
///
/// Standard-node and schema-absent branches are exercised indirectly through
/// {@code WorkflowBuilderTest}; this suite targets only the sub-workflow specific
/// imports/writes checks introduced with the sub-workflow DSL.
class WorkflowValidatorSubWorkflowTest {

    @Test
    void shouldPassWhenSubWorkflowImportsAndWritesAreDeclared() {
        Workflow workflow =
                workflowWithSchema(
                        List.of(
                                new StateVariableDeclaration("draft", VarType.STRING, true),
                                new StateVariableDeclaration("tl_dr", VarType.STRING, false)),
                        subWorkflowNode(Map.of("draft", "draft"), Map.of("tl_dr", "tl_dr")));

        assertThatCode(() -> WorkflowValidator.validate(workflow)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectImportsNotDeclaredInParentSchema() {
        // Parent schema declares only 'tl_dr'. Importing 'draft' would silently copy a
        // non-existent variable into the child, giving it an empty value with no warning.
        Workflow workflow =
                workflowWithSchema(
                        List.of(new StateVariableDeclaration("tl_dr", VarType.STRING, false)),
                        subWorkflowNode(Map.of("draft", "draft"), Map.of("tl_dr", "tl_dr")));

        assertThatThrownBy(() -> WorkflowValidator.validate(workflow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delegate")
                .hasMessageContaining("imports 'draft'")
                .hasMessageContaining("not declared in parent state schema");
    }

    @Test
    void shouldRejectWritesNotDeclaredInParentSchema() {
        // Parent schema declares only 'draft'. Child writes 'tl_dr' would land in the
        // parent state under an undeclared key — silently lost or, worse, colliding.
        Workflow workflow =
                workflowWithSchema(
                        List.of(new StateVariableDeclaration("draft", VarType.STRING, true)),
                        subWorkflowNode(Map.of("draft", "draft"), Map.of("tl_dr", "tl_dr")));

        assertThatThrownBy(() -> WorkflowValidator.validate(workflow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delegate")
                .hasMessageContaining("writes 'tl_dr'")
                .hasMessageContaining("not declared in parent state schema");
    }

    private static Workflow workflowWithSchema(
            List<StateVariableDeclaration> variables, SubWorkflowNode node) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        nodes.put(node.getId(), node);
        return Workflow.builder()
                .id("parent")
                .startNode(node.getId())
                .nodes(nodes)
                .stateSchema(new WorkflowStateSchema(variables))
                .build();
    }

    private static SubWorkflowNode subWorkflowNode(
            Map<String, String> inputs, Map<String, String> outputs) {
        return SubWorkflowNode.builder()
                .id("delegate")
                .workflowId("sub-target")
                .inputMapping(inputs)
                .outputMapping(outputs)
                .build();
    }
}
