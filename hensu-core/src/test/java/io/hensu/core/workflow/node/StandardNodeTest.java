package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandardNodeTest {

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
}
