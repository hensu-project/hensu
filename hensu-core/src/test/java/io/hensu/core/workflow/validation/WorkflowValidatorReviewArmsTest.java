package io.hensu.core.workflow.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.state.StateVariableDeclaration;
import io.hensu.core.workflow.state.VarType;
import io.hensu.core.workflow.state.WorkflowStateSchema;
import io.hensu.core.workflow.transition.AlwaysTransition;
import io.hensu.core.workflow.transition.ApprovalTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Load-time checks that a human verdict always has somewhere to go, and that engine variables
/// stay engine-owned.
@DisplayName("WorkflowValidator — review arms and engine variables")
class WorkflowValidatorReviewArmsTest {

    @Test
    @DisplayName("a review node declaring only onApproval fails the build")
    void rejectsHalfDeclaredArms() {
        Workflow workflow = reviewWorkflow(List.of(new ApprovalTransition(true, "end")), List.of());

        assertThatThrownBy(() -> WorkflowValidator.validate(workflow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("onRejection");
    }

    @Test
    @DisplayName("a trailing unconditional rule absorbs the missing arm — warning, not error")
    void acceptsMissingArmWithTrailingCatchAll() {
        Workflow workflow =
                reviewWorkflow(
                        List.of(new ApprovalTransition(true, "end"), new AlwaysTransition("end")),
                        List.of());

        assertThatCode(() -> WorkflowValidator.validate(workflow)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a trailing success rule absorbs the missing arm too")
    void acceptsMissingArmWithTrailingSuccess() {
        Workflow workflow =
                reviewWorkflow(
                        List.of(new ApprovalTransition(false, "end"), new SuccessTransition("end")),
                        List.of());

        assertThatCode(() -> WorkflowValidator.validate(workflow)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a review node with no approval arm is valid — rejection is terminal by design")
    void acceptsReviewNodeWithoutArms() {
        Workflow workflow = reviewWorkflow(List.of(new SuccessTransition("end")), List.of());

        assertThatCode(() -> WorkflowValidator.validate(workflow)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("both arms declared is the ordinary well-formed shape")
    void acceptsBothArms() {
        Workflow workflow =
                reviewWorkflow(
                        List.of(
                                new ApprovalTransition(true, "end"),
                                new ApprovalTransition(false, "end")),
                        List.of());

        assertThatCode(() -> WorkflowValidator.validate(workflow)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a programmatic workflow cannot claim an engine variable in writes")
    void rejectsEngineVariableInWrites() {
        Workflow workflow =
                reviewWorkflow(
                        List.of(
                                new ApprovalTransition(true, "end"),
                                new ApprovalTransition(false, "end")),
                        List.of("approved"));

        assertThatThrownBy(() -> WorkflowValidator.validate(workflow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserved engine variable");
    }

    // — Fixtures ——————————————————————————————————————————————————————————

    private Workflow reviewWorkflow(List<TransitionRule> rules, List<String> writes) {
        StandardNode review =
                StandardNode.builder()
                        .id("review")
                        .transitionRules(rules)
                        .writes(writes)
                        .reviewConfig(new ReviewConfig(ReviewMode.REQUIRED, true, true))
                        .build();
        StandardNode end =
                StandardNode.builder()
                        .id("end")
                        .transitionRules(List.of())
                        .writes(List.of())
                        .build();

        Map<String, Node> nodes = new LinkedHashMap<>();
        nodes.put("review", review);
        nodes.put("end", end);

        return Workflow.builder()
                .id("wf")
                .startNode("review")
                .nodes(nodes)
                .stateSchema(
                        new WorkflowStateSchema(
                                List.of(
                                        new StateVariableDeclaration(
                                                "draft", VarType.STRING, true))))
                .build();
    }
}
