package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.rubric.RubricNotFoundException;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.rubric.model.ComparisonOperator;
import io.hensu.core.rubric.model.Criterion;
import io.hensu.core.rubric.model.Rubric;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.workflow.WorkflowTest;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.ScoreTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowExecutorRubricTest extends WorkflowExecutorTestBase {

    // — Auto-backtrack ————————————————————————————————————————————————————

    @Test
    void shouldAutoBacktrackOnMinorFailure() throws Exception {
        // score 75 (< 80 threshold) on first attempt → auto-backtrack → retry → score 90 →
        // SUCCESS.
        when(rubricEngine.getRubric("quality")).thenReturn(Optional.of(qualityRubric()));
        when(rubricEngine.evaluate(eq("quality"), any(), any()))
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(75.0)
                                .passed(false)
                                .build())
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(90.0)
                                .passed(true)
                                .build());

        var work =
                StandardNode.builder()
                        .id("work")
                        .agentId("test-agent")
                        .prompt("Do work")
                        .rubricId("quality")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("backtrack-minor")
                        .agent(agentCfg())
                        .rubric("quality", "test-path")
                        .startNode(work)
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("First attempt"))
                .thenReturn(AgentResponse.TextResponse.of("Improved attempt"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void shouldStopBacktrackAfterMaxRetries() throws Exception {
        // rubric always returns score 75 (minor failure); after 3 backtracks
        // the auto-retry limit is reached and normal SuccessTransition fires.
        when(rubricEngine.getRubric("quality")).thenReturn(Optional.of(qualityRubric()));
        when(rubricEngine.evaluate(eq("quality"), any(), any()))
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(75.0)
                                .passed(false)
                                .build());

        var work =
                StandardNode.builder()
                        .id("work")
                        .agentId("test-agent")
                        .prompt("Do work")
                        .rubricId("quality")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("backtrack-exhaust")
                        .agent(agentCfg())
                        .rubric("quality", "test-path")
                        .startNode(work)
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Output"));

        var result = executor.execute(workflow, new HashMap<>());

        // After exhausting retries the SuccessTransition fires → SUCCESS.
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
    }

    // — ScoreTransition precedence over auto-backtrack ———————————————————

    @Test
    void shouldPreferOnScoreOverAutoBacktrack() throws Exception {
        // score 60 would trigger auto-backtrack, but ScoreTransition LT 70 → "revise"
        // takes precedence, routing to a FAILURE end node.
        when(rubricEngine.getRubric("quality")).thenReturn(Optional.of(qualityRubric()));
        when(rubricEngine.evaluate(eq("quality"), any(), any()))
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(60.0)
                                .passed(false)
                                .build());

        var review =
                StandardNode.builder()
                        .id("review")
                        .agentId("test-agent")
                        .prompt("Review this")
                        .rubricId("quality")
                        .transitionRules(
                                List.of(
                                        new ScoreTransition(
                                                List.of(
                                                        new ScoreCondition(
                                                                ComparisonOperator.LT,
                                                                70.0,
                                                                null,
                                                                "revise"))),
                                        new SuccessTransition("success-end")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("score-precedence")
                        .agent(agentCfg())
                        .rubric("quality", "test-path")
                        .startNode(review)
                        .node(failEnd("revise"))
                        .node(end("success-end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("{\"score\": 60}"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.FAILURE);
    }

    // — Stale score isolation between nodes ———————————————————————————————

    @Test
    void shouldNotLeakRubricScoreBetweenNodes() throws RubricNotFoundException {
        // node1 evaluates rubric (score 85); node2 has no rubricId but uses ScoreTransition.
        // If the score leaked, node2 would route "good". It must NOT — the score must be
        // cleared between nodes and throw instead.
        when(rubricEngine.getRubric("quality")).thenReturn(Optional.of(qualityRubric()));
        when(rubricEngine.evaluate(eq("quality"), any(), any()))
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(85.0)
                                .passed(true)
                                .build());

        var node1 =
                StandardNode.builder()
                        .id("node1")
                        .agentId("test-agent")
                        .prompt("Step 1")
                        .rubricId("quality")
                        .transitionRules(List.of(new SuccessTransition("node2")))
                        .build();
        var node2 =
                StandardNode.builder()
                        .id("node2")
                        .agentId("test-agent")
                        .prompt("Step 2")
                        // no rubricId — score must not bleed from node1
                        .transitionRules(
                                List.of(
                                        new ScoreTransition(
                                                List.of(
                                                        new ScoreCondition(
                                                                ComparisonOperator.GTE,
                                                                80.0,
                                                                null,
                                                                "good")))))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("stale-rubric")
                        .agent(agentCfg())
                        .rubric("quality", "test-path")
                        .startNode(node1)
                        .node(node2)
                        .node(end("good"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Output"));

        assertThatThrownBy(() -> executor.execute(workflow, new HashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No valid transition");
    }

    // — Helpers ——————————————————————————————————————————————————————————————

    private static Rubric qualityRubric() {
        return Rubric.builder()
                .id("quality")
                .name("Quality")
                .criteria(
                        List.of(
                                Criterion.builder()
                                        .id("overall")
                                        .name("Overall Quality")
                                        .weight(1.0)
                                        .minScore(70.0)
                                        .build()))
                .build();
    }
}
