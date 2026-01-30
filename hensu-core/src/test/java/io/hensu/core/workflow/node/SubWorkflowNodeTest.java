package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubWorkflowNodeTest {

    @Test
    void shouldCreateSubWorkflowNodeWithAllFields() {
        // Given
        Map<String, String> inputMapping = Map.of("input1", "context.data");
        Map<String, String> outputMapping = Map.of("result", "output.value");
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        // When
        SubWorkflowNode node =
                new SubWorkflowNode(
                        "sub-1", "nested-workflow", inputMapping, outputMapping, transitions);

        // Then
        assertThat(node.getId()).isEqualTo("sub-1");
        assertThat(node.getWorkflowId()).isEqualTo("nested-workflow");
        assertThat(node.getInputMapping()).containsEntry("input1", "context.data");
        assertThat(node.getOutputMapping()).containsEntry("result", "output.value");
        assertThat(node.getTransitionRules()).hasSize(1);
    }

    @Test
    void shouldReturnSubWorkflowNodeType() {
        // When
        SubWorkflowNode node = new SubWorkflowNode("sub-1", "nested", null, null, null);

        // Then
        assertThat(node.getNodeType()).isEqualTo(NodeType.SUB_WORKFLOW);
    }

    @Test
    void shouldReturnEmptyRubricId() {
        // When
        SubWorkflowNode node = new SubWorkflowNode("sub-1", "nested", null, null, null);

        // Then
        assertThat(node.getRubricId()).isEmpty();
    }

    @Test
    void shouldAllowNullMappings() {
        // When
        SubWorkflowNode node = new SubWorkflowNode("sub-1", "nested", null, null, null);

        // Then
        assertThat(node.getInputMapping()).isNull();
        assertThat(node.getOutputMapping()).isNull();
        assertThat(node.getTransitionRules()).isNull();
    }

    @Test
    void shouldImplementEquals() {
        // Given
        Map<String, String> inputMapping = Map.of("input", "value");
        SubWorkflowNode node1 = new SubWorkflowNode("sub-1", "nested", inputMapping, null, null);
        SubWorkflowNode node2 = new SubWorkflowNode("sub-1", "nested", inputMapping, null, null);
        SubWorkflowNode node3 = new SubWorkflowNode("sub-2", "nested", inputMapping, null, null);

        // Then
        assertThat(node1).isEqualTo(node2);
        assertThat(node1).isNotEqualTo(node3);
        assertThat(node1.hashCode()).isEqualTo(node2.hashCode());
    }

    @Test
    void shouldReturnMeaningfulToString() {
        // Given
        SubWorkflowNode node =
                new SubWorkflowNode("sub-1", "nested-workflow", Map.of("key", "value"), null, null);

        // When
        String toString = node.toString();

        // Then
        assertThat(toString).contains("sub-1");
        assertThat(toString).contains("nested-workflow");
        assertThat(toString).contains("inputMapping");
    }

    @Test
    void shouldEqualItself() {
        // Given
        SubWorkflowNode node = new SubWorkflowNode("sub-1", "nested", null, null, null);

        // Then
        assertThat(node).isEqualTo(node);
    }

    @Test
    void shouldNotEqualNull() {
        // Given
        SubWorkflowNode node = new SubWorkflowNode("sub-1", "nested", null, null, null);

        // Then
        assertThat(node).isNotEqualTo(null);
    }

    @Test
    void shouldNotEqualDifferentClass() {
        // Given
        SubWorkflowNode node = new SubWorkflowNode("sub-1", "nested", null, null, null);

        // Then
        assertThat(node).isNotEqualTo("string");
    }
}
