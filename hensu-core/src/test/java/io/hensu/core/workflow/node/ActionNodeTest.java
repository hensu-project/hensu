package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.action.Action;
import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionNodeTest {

    @Test
    void shouldThrowExceptionWhenIdIsNull() {
        List<Action> actions = List.of(new Action.Execute("test"));
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        assertThatThrownBy(
                        () ->
                                ActionNode.builder()
                                        .actions(actions)
                                        .transitionRules(transitions)
                                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id");
    }

    @Test
    void shouldThrowExceptionWhenActionsAreNull() {
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        assertThatThrownBy(
                        () ->
                                ActionNode.builder()
                                        .id("test-node")
                                        .transitionRules(transitions)
                                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("actions");
    }

    @Test
    void shouldThrowExceptionWhenTransitionRulesAreNull() {
        List<Action> actions = List.of(new Action.Execute("test"));

        assertThatThrownBy(() -> ActionNode.builder().id("test-node").actions(actions).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transitionRules");
    }

    @Test
    void shouldMakeActionsImmutable() {
        ActionNode node = buildMinimal();

        assertThatThrownBy(() -> node.getActions().add(new Action.Execute("another")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldMakeTransitionRulesImmutable() {
        ActionNode node = buildMinimal();

        assertThatThrownBy(() -> node.getTransitionRules().add(new SuccessTransition("another")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private ActionNode buildMinimal() {
        return ActionNode.builder()
                .id("action")
                .actions(List.of(new Action.Execute("test")))
                .transitionRules(List.of(new SuccessTransition("next")))
                .build();
    }
}
