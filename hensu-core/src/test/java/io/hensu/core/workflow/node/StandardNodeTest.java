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
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        StandardNode node =
                StandardNode.builder()
                        .id("test-node")
                        .agentId("test-agent")
                        .prompt("Test prompt")
                        .transitionRules(transitions)
                        .build();

        assertThat(node.getId()).isEqualTo("test-node");
        assertThat(node.getAgentId()).isEqualTo("test-agent");
        assertThat(node.getPrompt()).isEqualTo("Test prompt");
        assertThat(node.getNodeType()).isEqualTo(NodeType.STANDARD);
        assertThat(node.getTransitionRules()).hasSize(1);
    }

    @Test
    void shouldBuildStandardNodeWithAllFields() {
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));
        ReviewConfig reviewConfig = new ReviewConfig(ReviewMode.REQUIRED, true, true);

        StandardNode node =
                StandardNode.builder()
                        .id("full-node")
                        .agentId("full-agent")
                        .prompt("Full prompt")
                        .rubricId("quality-rubric")
                        .reviewConfig(reviewConfig)
                        .transitionRules(transitions)
                        .writes(List.of("article", "score", "recommendation"))
                        .build();

        assertThat(node.getId()).isEqualTo("full-node");
        assertThat(node.getAgentId()).isEqualTo("full-agent");
        assertThat(node.getRubricId()).isEqualTo("quality-rubric");
        assertThat(node.getReviewConfig()).isEqualTo(reviewConfig);
        assertThat(node.getWrites()).containsExactly("article", "score", "recommendation");
    }

    @Test
    void shouldAllowNullAgentIdAndPrompt() {
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        StandardNode node =
                StandardNode.builder().id("no-agent-node").transitionRules(transitions).build();

        assertThat(node.getAgentId()).isNull();
        assertThat(node.getPrompt()).isNull();
    }

    @Test
    void shouldThrowExceptionWhenIdIsNull() {
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        assertThatThrownBy(() -> StandardNode.builder().transitionRules(transitions).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id");
    }

    @Test
    void shouldThrowExceptionWhenTransitionRulesAreNull() {
        assertThatThrownBy(() -> StandardNode.builder().id("test-node").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transitionRules");
    }

    @Test
    void shouldReturnEmptyWritesWhenNotSet() {
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        StandardNode node =
                StandardNode.builder().id("test-node").transitionRules(transitions).build();

        assertThat(node.getWrites()).isEmpty();
    }

    @Test
    void shouldMakeWritesImmutable() {
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        StandardNode node =
                StandardNode.builder()
                        .id("test-node")
                        .transitionRules(transitions)
                        .writes(List.of("article"))
                        .build();

        assertThatThrownBy(() -> node.getWrites().add("score"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldImplementEqualsBasedOnIdAndAgentId() {
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

        assertThat(node1).isEqualTo(node2);
        assertThat(node1).isNotEqualTo(node3);
        assertThat(node1.hashCode()).isEqualTo(node2.hashCode());
    }
}
