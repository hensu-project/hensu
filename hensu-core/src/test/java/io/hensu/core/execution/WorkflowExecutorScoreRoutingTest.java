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
import io.hensu.core.rubric.model.DoubleRange;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.workflow.WorkflowTest;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.ScoreTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WorkflowExecutorScoreRoutingTest extends WorkflowExecutorTestBase {

    @ParameterizedTest(name = "score={0} → {2}")
    @MethodSource("scoreRoutingCases")
    void shouldRouteBySimpleScoreThreshold(double score, boolean passed, ExitStatus expected)
            throws Exception {
        when(rubricEngine.exists("quality")).thenReturn(true);
        when(rubricEngine.evaluate(eq("quality"), any(), any()))
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(score)
                                .passed(passed)
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
                                                                ComparisonOperator.GTE,
                                                                80.0,
                                                                null,
                                                                "excellent"),
                                                        new ScoreCondition(
                                                                ComparisonOperator.LT,
                                                                80.0,
                                                                null,
                                                                "poor")))))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("score-route")
                        .agent(agentCfg())
                        .rubric("quality", "test-path")
                        .startNode(review)
                        .node(end("excellent"))
                        .node(failEnd("poor"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Work output"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus()).isEqualTo(expected);
    }

    static Stream<Arguments> scoreRoutingCases() {
        return Stream.of(
                Arguments.of(90.0, true, ExitStatus.SUCCESS), // GTE 80 → "excellent"
                Arguments.of(40.0, false, ExitStatus.FAILURE) // LT 80 → "poor"
                );
    }

    @Test
    void shouldRouteByScoreRange() throws Exception {
        // Score 75 matches RANGE 70..89 → routes to "good".
        // Tests the RANGE operator — not covered by the simple threshold cases above.
        when(rubricEngine.exists("quality")).thenReturn(true);
        when(rubricEngine.evaluate(eq("quality"), any(), any()))
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(75.0)
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
                                                                ComparisonOperator.GTE,
                                                                90.0,
                                                                null,
                                                                "excellent"),
                                                        new ScoreCondition(
                                                                ComparisonOperator.RANGE,
                                                                null,
                                                                new DoubleRange(70.0, 89.0),
                                                                "good"),
                                                        new ScoreCondition(
                                                                ComparisonOperator.LT,
                                                                70.0,
                                                                null,
                                                                "poor")))))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("score-range")
                        .agent(agentCfg())
                        .rubric("quality", "test-path")
                        .startNode(review)
                        .node(end("excellent"))
                        .node(end("good"))
                        .node(failEnd("poor"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Good work"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void shouldAutoApproveWhenRubricPasses() throws Exception {
        // rubric.passed=true with a single GTE-80 condition that is satisfied → SUCCESS.
        when(rubricEngine.exists("quality")).thenReturn(true);
        when(rubricEngine.evaluate(eq("quality"), any(), any()))
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(90.0)
                                .passed(true)
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
                                                                ComparisonOperator.GTE,
                                                                80.0,
                                                                null,
                                                                "end"))),
                                        new SuccessTransition("end")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("score-pass")
                        .agent(agentCfg())
                        .rubric("quality", "test-path")
                        .startNode(review)
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("OK work"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void shouldThrowWhenNoScoreConditionMatches() throws RubricNotFoundException {
        // Score 85 with a single GTE-90 condition → no match → IllegalStateException.
        // rubric.passed=true prevents auto-backtrack from kicking in.
        when(rubricEngine.exists("quality")).thenReturn(true);
        when(rubricEngine.evaluate(eq("quality"), any(), any()))
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(85.0)
                                .passed(true)
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
                                                                ComparisonOperator.GTE,
                                                                90.0,
                                                                null,
                                                                "excellent")))))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("score-nomatch")
                        .agent(agentCfg())
                        .rubric("quality", "test-path")
                        .startNode(review)
                        .node(end("excellent"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Good work"));

        assertThatThrownBy(() -> executor.execute(workflow, new HashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No valid transition");
    }
}
