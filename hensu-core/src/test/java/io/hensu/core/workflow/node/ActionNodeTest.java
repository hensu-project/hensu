package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.action.Action;
import io.hensu.core.workflow.transition.FailureTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionNodeTest {

    @Test
    void shouldBuildActionNodeWithRequiredFields() {
        // Given
        List<Action> actions = List.of(new Action.Execute("git-commit"));
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        // When
        ActionNode node =
                ActionNode.builder()
                        .id("commit-action")
                        .actions(actions)
                        .transitionRules(transitions)
                        .build();

        // Then
        assertThat(node.getId()).isEqualTo("commit-action");
        assertThat(node.getActions()).hasSize(1);
        assertThat(node.getNodeType()).isEqualTo(NodeType.ACTION);
        assertThat(node.getTransitionRules()).hasSize(1);
    }

    @Test
    void shouldBuildActionNodeWithMultipleActions() {
        // Given
        List<Action> actions =
                List.of(
                        new Action.Execute("git-commit"),
                        new Action.Send("slack", Map.of("message", "Build completed")),
                        new Action.Send("webhook"));
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        // When
        ActionNode node =
                ActionNode.builder()
                        .id("multi-action")
                        .actions(actions)
                        .transitionRules(transitions)
                        .build();

        // Then
        assertThat(node.getActions()).hasSize(3);
        assertThat(node.getActions().get(0)).isInstanceOf(Action.Execute.class);
        assertThat(node.getActions().get(1)).isInstanceOf(Action.Send.class);
        assertThat(node.getActions().get(2)).isInstanceOf(Action.Send.class);
    }

    @Test
    void shouldBuildActionNodeWithMultipleTransitions() {
        // Given
        List<Action> actions = List.of(new Action.Execute("deploy"));
        List<TransitionRule> transitions =
                List.of(new SuccessTransition("success"), new FailureTransition(2, "rollback"));

        // When
        ActionNode node =
                ActionNode.builder()
                        .id("deploy-action")
                        .actions(actions)
                        .transitionRules(transitions)
                        .build();

        // Then
        assertThat(node.getTransitionRules()).hasSize(2);
    }

    @Test
    void shouldThrowExceptionWhenIdIsNull() {
        // Given
        List<Action> actions = List.of(new Action.Execute("test"));
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        // When/Then
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
        // Given
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        // When/Then
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
        // Given
        List<Action> actions = List.of(new Action.Execute("test"));

        // When/Then
        assertThatThrownBy(() -> ActionNode.builder().id("test-node").actions(actions).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transitionRules");
    }

    @Test
    void shouldReturnNullForRubricId() {
        // Given
        List<Action> actions = List.of(new Action.Execute("test"));
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        ActionNode node =
                ActionNode.builder()
                        .id("action")
                        .actions(actions)
                        .transitionRules(transitions)
                        .build();

        // Then - action nodes don't have rubrics
        assertThat(node.getRubricId()).isNull();
    }

    @Test
    void shouldMakeActionsImmutable() {
        // Given
        List<Action> actions = List.of(new Action.Execute("test"));
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        ActionNode node =
                ActionNode.builder()
                        .id("action")
                        .actions(actions)
                        .transitionRules(transitions)
                        .build();

        // Then
        assertThatThrownBy(() -> node.getActions().add(new Action.Execute("another")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldMakeTransitionRulesImmutable() {
        // Given
        List<Action> actions = List.of(new Action.Execute("test"));
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        ActionNode node =
                ActionNode.builder()
                        .id("action")
                        .actions(actions)
                        .transitionRules(transitions)
                        .build();

        // Then
        assertThatThrownBy(() -> node.getTransitionRules().add(new SuccessTransition("another")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldImplementEqualsBasedOnId() {
        // Given
        List<Action> actions1 = List.of(new Action.Execute("cmd1"));
        List<Action> actions2 = List.of(new Action.Execute("cmd2"));
        List<TransitionRule> transitions = List.of(new SuccessTransition("next"));

        ActionNode node1 =
                ActionNode.builder()
                        .id("action-1")
                        .actions(actions1)
                        .transitionRules(transitions)
                        .build();

        ActionNode node2 =
                ActionNode.builder()
                        .id("action-1")
                        .actions(actions2) // Different actions
                        .transitionRules(transitions)
                        .build();

        ActionNode node3 =
                ActionNode.builder()
                        .id("action-2") // Different ID
                        .actions(actions1)
                        .transitionRules(transitions)
                        .build();

        // Then
        assertThat(node1).isEqualTo(node2); // Same ID
        assertThat(node1).isNotEqualTo(node3); // Different ID
        assertThat(node1.hashCode()).isEqualTo(node2.hashCode());
    }

    @Test
    void shouldCreateSendActionWithPayload() {
        // Given
        Map<String, Object> payload = Map.of("message", "Hello", "channel", "#general");
        Action.Send send = new Action.Send("slack", payload);

        // Then
        assertThat(send.getHandlerId()).isEqualTo("slack");
        assertThat(send.getPayload()).containsEntry("message", "Hello");
        assertThat(send.getPayload()).containsEntry("channel", "#general");
    }

    @Test
    void shouldCreateSendActionWithEmptyPayload() {
        // Given
        Action.Send send = new Action.Send("webhook");

        // Then
        assertThat(send.getHandlerId()).isEqualTo("webhook");
        assertThat(send.getPayload()).isEmpty();
    }
}
