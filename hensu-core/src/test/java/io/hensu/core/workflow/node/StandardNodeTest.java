package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandardNodeTest {

    @Test
    void shouldBuildStandardNodeWithRequiredFields() {
        // Given
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        // When
        StandardNode node =
                StandardNode.builder()
                        .id("test-node")
                        .agentId("test-agent")
                        .prompt("Test prompt")
                        .transitionRules(transitions)
                        .build();

        // Then
        assertThat(node.getId()).isEqualTo("test-node");
        assertThat(node.getAgentId()).isEqualTo("test-agent");
        assertThat(node.getPrompt()).isEqualTo("Test prompt");
        assertThat(node.getNodeType()).isEqualTo(NodeType.STANDARD);
        assertThat(node.getTransitionRules()).hasSize(1);
    }

    @Test
    void shouldBuildStandardNodeWithAllFields() {
        // Given
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));
        ReviewConfig reviewConfig = new ReviewConfig(ReviewMode.REQUIRED, true, true);
        List<String> outputParams = List.of("param1", "param2");

        // When
        StandardNode node =
                StandardNode.builder()
                        .id("full-node")
                        .agentId("full-agent")
                        .prompt("Full prompt")
                        .rubricId("quality-rubric")
                        .reviewConfig(reviewConfig)
                        .transitionRules(transitions)
                        .outputParams(outputParams)
                        .build();

        // Then
        assertThat(node.getId()).isEqualTo("full-node");
        assertThat(node.getAgentId()).isEqualTo("full-agent");
        assertThat(node.getPrompt()).isEqualTo("Full prompt");
        assertThat(node.getRubricId()).isEqualTo("quality-rubric");
        assertThat(node.getReviewConfig()).isEqualTo(reviewConfig);
        assertThat(node.getOutputParams()).containsExactly("param1", "param2");
    }

    @Test
    void shouldAllowNullAgentIdAndPrompt() {
        // Given - nodes can have null agentId/prompt for non-agent execution strategies
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        // When
        StandardNode node =
                StandardNode.builder().id("no-agent-node").transitionRules(transitions).build();

        // Then
        assertThat(node.getId()).isEqualTo("no-agent-node");
        assertThat(node.getAgentId()).isNull();
        assertThat(node.getPrompt()).isNull();
    }

    @Test
    void shouldThrowExceptionWhenIdIsNull() {
        // Given
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        // When/Then
        assertThatThrownBy(() -> StandardNode.builder().transitionRules(transitions).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id");
    }

    @Test
    void shouldThrowExceptionWhenTransitionRulesAreNull() {
        // When/Then
        assertThatThrownBy(() -> StandardNode.builder().id("test-node").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transitionRules");
    }

    @Test
    void shouldReturnEmptyOutputParamsWhenNotSet() {
        // Given
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        // When
        StandardNode node =
                StandardNode.builder().id("test-node").transitionRules(transitions).build();

        // Then
        assertThat(node.getOutputParams()).isEmpty();
    }

    @Test
    void shouldMakeOutputParamsImmutable() {
        // Given
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        // When
        StandardNode node =
                StandardNode.builder()
                        .id("test-node")
                        .transitionRules(transitions)
                        .outputParams(List.of("param1"))
                        .build();

        // Then
        assertThatThrownBy(() -> node.getOutputParams().add("param2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldImplementEqualsBasedOnIdAndAgentId() {
        // Given
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        StandardNode node1 =
                StandardNode.builder()
                        .id("node-1")
                        .agentId("agent-1")
                        .transitionRules(transitions)
                        .build();

        StandardNode node2 =
                StandardNode.builder()
                        .id("node-1")
                        .agentId("agent-1")
                        .prompt("Different prompt")
                        .transitionRules(transitions)
                        .build();

        StandardNode node3 =
                StandardNode.builder()
                        .id("node-2")
                        .agentId("agent-1")
                        .transitionRules(transitions)
                        .build();

        // Then
        assertThat(node1).isEqualTo(node2);
        assertThat(node1).isNotEqualTo(node3);
        assertThat(node1.hashCode()).isEqualTo(node2.hashCode());
    }

    @Test
    void shouldReturnMeaningfulToString() {
        // Given
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        StandardNode node =
                StandardNode.builder()
                        .id("test-node")
                        .agentId("test-agent")
                        .transitionRules(transitions)
                        .build();

        // When
        String toString = node.toString();

        // Then
        assertThat(toString).contains("test-node");
        assertThat(toString).contains("test-agent");
    }
}
