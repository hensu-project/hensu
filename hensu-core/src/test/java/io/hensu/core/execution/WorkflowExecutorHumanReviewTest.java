package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.workflow.WorkflowTest;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowExecutorHumanReviewTest extends WorkflowExecutorTestBase {

    private ReviewHandler mockReviewHandler;

    @BeforeEach
    void setUpReviewExecutor() {
        mockReviewHandler = mock(ReviewHandler.class);
        executor =
                new WorkflowExecutor(
                        new DefaultNodeExecutorRegistry(),
                        agentRegistry,
                        executorService,
                        rubricEngine,
                        mockReviewHandler);
    }

    // — Review mode routing ———————————————————————————————————————————————

    @Test
    void shouldAutoApproveWhenReviewDisabled() throws Exception {
        var workflow = workflowWithReview("review-disabled", ReviewMode.DISABLED);

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        verify(mockReviewHandler, never()).requestReview(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldAutoApproveSuccessWhenReviewOptional() throws Exception {
        // OPTIONAL + agent SUCCESS → reviewHandler never called.
        var workflow = workflowWithReview("review-optional-success", ReviewMode.OPTIONAL);

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        verify(mockReviewHandler, never()).requestReview(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldAlwaysRequestReviewWhenRequired() throws Exception {
        when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ReviewDecision.Approve(null));
        var workflow = workflowWithReview("review-required", ReviewMode.REQUIRED);

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        verify(mockReviewHandler).requestReview(any(), any(), any(), any(), any(), any());
    }

    // — Review decisions ——————————————————————————————————————————————————

    @Test
    void shouldRejectWorkflowOnReviewRejection() throws Exception {
        when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ReviewDecision.Reject("Quality insufficient"));
        var workflow = workflowWithReview("review-reject", ReviewMode.REQUIRED);

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Rejected.class);
        assertThat(((ExecutionResult.Rejected) result).getReason())
                .isEqualTo("Quality insufficient");
    }

    @Test
    void shouldBacktrackOnReviewBacktrack() throws Exception {
        // First review backtracks to step1; second approves. step1 executes twice → 4+ history
        // entries.
        when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ReviewDecision.Backtrack("step1", "Redo step 1"))
                .thenReturn(new ReviewDecision.Approve(null));

        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("review-backtrack")
                        .agent(agentCfg())
                        .startNode(step("step1", "step2"))
                        .node(
                                StandardNode.builder()
                                        .id("step2")
                                        .agentId("test-agent")
                                        .prompt("Step 2")
                                        .reviewConfig(
                                                new ReviewConfig(ReviewMode.REQUIRED, true, true))
                                        .transitionRules(List.of(new SuccessTransition("end")))
                                        .build())
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Output"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
        // step1 executed twice + step2 executed twice → at least 4 history entries.
        assertThat(
                        ((ExecutionResult.Completed) result)
                                .getFinalState()
                                .getHistory()
                                .getSteps()
                                .size())
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void shouldThrowWhenBacktrackTargetsNodeNotInWorkflow() {
        // handleBacktrack() sets currentNode = "ghost-node", then the loop calls
        // workflow.getNodes().get("ghost-node") == null → IllegalStateException.
        // If someone adds silent null-handling instead of throwing, this test fails.
        when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ReviewDecision.Backtrack("ghost-node", "go back"));

        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("backtrack-invalid")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("step")
                                        .agentId("test-agent")
                                        .prompt("Work")
                                        .reviewConfig(
                                                new ReviewConfig(ReviewMode.REQUIRED, true, true))
                                        .transitionRules(List.of(new SuccessTransition("end")))
                                        .build())
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        assertThatThrownBy(() -> executor.execute(workflow, new HashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost-node");
    }

    // — Helpers ———————————————————————————————————————————————————————————

    private io.hensu.core.workflow.Workflow workflowWithReview(String id, ReviewMode mode) {
        return WorkflowTest.TestWorkflowBuilder.create(id)
                .agent(agentCfg())
                .startNode(
                        StandardNode.builder()
                                .id("step")
                                .agentId("test-agent")
                                .prompt("Work")
                                .reviewConfig(new ReviewConfig(mode, false, false))
                                .transitionRules(List.of(new SuccessTransition("end")))
                                .build())
                .node(end("end"))
                .build();
    }
}
