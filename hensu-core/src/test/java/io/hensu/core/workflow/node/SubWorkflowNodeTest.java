package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                SubWorkflowNode.builder()
                        .id("sub-1")
                        .workflowId("nested-workflow")
                        .inputMapping(inputMapping)
                        .outputMapping(outputMapping)
                        .transitionRules(transitions)
                        .build();

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
        SubWorkflowNode node = SubWorkflowNode.builder().id("sub-1").workflowId("nested").build();

        // Then
        assertThat(node.getNodeType()).isEqualTo(NodeType.SUB_WORKFLOW);
    }

    @Test
    void shouldReturnNullRubricId() {
        // When
        SubWorkflowNode node = SubWorkflowNode.builder().id("sub-1").workflowId("nested").build();

        // Then
        assertThat(node.getRubricId()).isNull();
    }

    @Test
    void shouldNormalizNullMappingsToEmpty() {
        // When — omitting optional fields defaults to empty collections
        SubWorkflowNode node = SubWorkflowNode.builder().id("sub-1").workflowId("nested").build();

        // Then
        assertThat(node.getInputMapping()).isEmpty();
        assertThat(node.getOutputMapping()).isEmpty();
        assertThat(node.getTransitionRules()).isEmpty();
    }

    @Test
    void shouldImplementEquals() {
        // Given
        Map<String, String> inputMapping = Map.of("input", "value");
        SubWorkflowNode node1 =
                SubWorkflowNode.builder()
                        .id("sub-1")
                        .workflowId("nested")
                        .inputMapping(inputMapping)
                        .build();
        SubWorkflowNode node2 =
                SubWorkflowNode.builder()
                        .id("sub-1")
                        .workflowId("nested")
                        .inputMapping(inputMapping)
                        .build();
        SubWorkflowNode node3 =
                SubWorkflowNode.builder()
                        .id("sub-2")
                        .workflowId("nested")
                        .inputMapping(inputMapping)
                        .build();

        // Then
        assertThat(node1).isEqualTo(node2);
        assertThat(node1).isNotEqualTo(node3);
        assertThat(node1.hashCode()).isEqualTo(node2.hashCode());
    }

    @Test
    void shouldThrowWhenIdMissing() {
        assertThatThrownBy(() -> SubWorkflowNode.builder().workflowId("nested").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id");
    }

    @Test
    void shouldThrowWhenWorkflowIdMissing() {
        assertThatThrownBy(() -> SubWorkflowNode.builder().id("sub-1").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflowId");
    }
}
