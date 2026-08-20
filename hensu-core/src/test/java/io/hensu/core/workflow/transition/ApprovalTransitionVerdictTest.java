package io.hensu.core.workflow.transition;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.review.ReviewVerdict;
import io.hensu.core.state.HensuState;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Verifies the precedence rule between a human verdict and the agent's own `approved`.
///
/// Regression cover for the case where an analyst emitted `"approved": false`, an operator
/// approved the node, and the workflow still ended at the rejection node because routing only
/// ever consulted the agent's value.
@DisplayName("ApprovalTransition verdict precedence")
class ApprovalTransitionVerdictTest {

    private static final NodeResult SUCCESS = new NodeResult(ResultStatus.SUCCESS, "out", Map.of());

    @Test
    @DisplayName("a human approval routes to the approval arm over an agent's self-rejection")
    void verdictOverridesAgentRejection() {
        HensuState state = stateWhereAgentSaid(false);
        state.setReviewVerdict(ReviewVerdict.approval());

        assertThat(new ApprovalTransition(true, "approved-end").evaluate(state, SUCCESS))
                .isEqualTo("approved-end");
        assertThat(new ApprovalTransition(false, "rejected-end").evaluate(state, SUCCESS)).isNull();
    }

    @Test
    @DisplayName("a human rejection routes to the rejection arm over an agent's self-approval")
    void verdictOverridesAgentApproval() {
        HensuState state = stateWhereAgentSaid(true);
        state.setReviewVerdict(ReviewVerdict.rejection("limit too high"));

        assertThat(new ApprovalTransition(false, "rejected-end").evaluate(state, SUCCESS))
                .isEqualTo("rejected-end");
        assertThat(new ApprovalTransition(true, "approved-end").evaluate(state, SUCCESS)).isNull();
    }

    @Test
    @DisplayName("with no verdict the agent's own value routes, as it does on review-less nodes")
    void fallsBackToAgentValue() {
        HensuState state = stateWhereAgentSaid(true);

        assertThat(new ApprovalTransition(true, "approved-end").evaluate(state, SUCCESS))
                .isEqualTo("approved-end");
    }

    private HensuState stateWhereAgentSaid(boolean approved) {
        Map<String, Object> context = new HashMap<>();
        context.put(EngineVariables.APPROVED, approved);
        return new HensuState.Builder()
                .executionId("test")
                .workflowId("test-wf")
                .currentNode("review")
                .context(context)
                .history(new ExecutionHistory())
                .build();
    }
}
