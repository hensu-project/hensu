package io.hensu.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.parallel.ConsensusStrategy;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.rubric.RubricNotFoundException;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.rubric.model.ComparisonOperator;
import io.hensu.core.rubric.model.DoubleRange;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.node.ActionNode;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.ForkNode;
import io.hensu.core.workflow.node.GenericNode;
import io.hensu.core.workflow.node.JoinNode;
import io.hensu.core.workflow.node.MergeStrategy;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.ParallelNode;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.FailureTransition;
import io.hensu.core.workflow.transition.ScoreTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutorTest {

    @Mock private AgentRegistry agentRegistry;
    @Mock private RubricEngine rubricEngine;
    @Mock private Agent mockAgent;

    private ExecutorService executorService;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(2);
        executor =
                new WorkflowExecutor(
                        new DefaultNodeExecutorRegistry(),
                        agentRegistry,
                        executorService,
                        rubricEngine,
                        ReviewHandler.AUTO_APPROVE);
    }

    @AfterEach
    void tearDown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Core routing and lifecycle
    // -------------------------------------------------------------------------

    @Test
    void shouldExecuteSimpleWorkflowToEnd() throws Exception {
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(step("start", "end"))
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Agent output"));

        var result = executor.execute(workflow, new HashMap<>(Map.of("input", "test data")));

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void shouldThrowExceptionWhenNodeNotFound() {
        // "start" transitions to "missing-node" which is absent from the nodes map.
        // The executor must throw at runtime when it tries to resolve the next step.
        var start =
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(List.of(new SuccessTransition("missing-node")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(start)
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Agent output"));

        assertThatThrownBy(() -> executor.execute(workflow, new HashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Node not found");
    }

    @Test
    void shouldThrowExceptionWhenAgentNotFound() {
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(step("start", "end"))
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor.execute(workflow, new HashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent not found");
    }

    @Test
    void shouldResolveTemplateVariables() throws Exception {
        // Prompt uses {topic} and {style}; executor must substitute from initial context.
        // If substitution fails the mock won't match and the test will fail.
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("start")
                                        .agentId("test-agent")
                                        .prompt("Write about {topic} in {style} style")
                                        .transitionRules(List.of(new SuccessTransition("end")))
                                        .build())
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(eq("Write about artificial intelligence in formal style"), any()))
                .thenReturn(AgentResponse.TextResponse.of("Generated content"));

        var ctx = new HashMap<String, Object>();
        ctx.put("topic", "artificial intelligence");
        ctx.put("style", "formal");
        var result = executor.execute(workflow, ctx);

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    void shouldRecordExecutionHistory() throws Exception {
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(step("step1", "step2"))
                        .node(step("step2", "end"))
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Agent output"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        // Both steps must appear in history — proves the loop visits each node once.
        assertThat(((ExecutionResult.Completed) result).getFinalState().getHistory().getSteps())
                .hasSize(2);
    }

    @Test
    void shouldHandleAgentFailureResponse() throws Exception {
        // Agent returns Error → executor follows FailureTransition → failure-end.
        var start =
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("success-end"),
                                        new FailureTransition(0, "failure-end")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(start)
                        .node(end("success-end"))
                        .node(failEnd("failure-end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.Error.of("Agent error"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.FAILURE);
    }

    @Test
    void shouldExecuteToFailureEnd() throws Exception {
        // Agent succeeds but the workflow design routes to a FAILURE end node.
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("start")
                                        .agentId("test-agent")
                                        .prompt("Process input")
                                        .transitionRules(
                                                List.of(new SuccessTransition("failure-end")))
                                        .build())
                        .node(failEnd("failure-end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Agent output"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.FAILURE);
    }

    @Test
    void shouldThrowWhenNoValidTransition() {
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("start")
                                        .agentId("test-agent")
                                        .prompt("Process input")
                                        .transitionRules(List.of()) // no transitions
                                        .build())
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Agent output"));

        assertThatThrownBy(() -> executor.execute(workflow, new HashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No valid transition");
    }

    @Test
    void shouldHandleEmptyPrompt() throws Exception {
        // null prompt → executor must not crash; sends empty string to agent.
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("start")
                                        .agentId("test-agent")
                                        .prompt(null)
                                        .transitionRules(List.of(new SuccessTransition("end")))
                                        .build())
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(eq(""), any()))
                .thenReturn(AgentResponse.TextResponse.of("Agent output"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    void shouldExtractParametersFromJsonOutput() throws Exception {
        // step1 returns JSON with outputParams; executor must extract and store in context.
        var step1 =
                StandardNode.builder()
                        .id("step1")
                        .agentId("test-agent")
                        .prompt("Extract facts about Georgia")
                        .outputParams(List.of("lake_name", "peak_height", "capital"))
                        .transitionRules(List.of(new SuccessTransition("step2")))
                        .build();
        var step2 =
                StandardNode.builder()
                        .id("step2")
                        .agentId("test-agent")
                        .prompt("Use the facts: {lake_name}, {peak_height}, {capital}")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("output-params")
                        .agent(agentCfg())
                        .startNode(step1)
                        .node(step2)
                        .node(end("end"))
                        .build();

        String jsonOutput =
                """
                Here are the facts:
                {
                    "lake_name": "Lake Paravani",
                    "peak_height": "5201m",
                    "capital": "Tbilisi"
                }
                """;
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of(jsonOutput))
                .thenReturn(AgentResponse.TextResponse.of("Final output using extracted params"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var ctx = ((ExecutionResult.Completed) result).getFinalState().getContext();
        assertThat(ctx.get("lake_name")).isEqualTo("Lake Paravani");
        assertThat(ctx.get("peak_height")).isEqualTo("5201m");
        assertThat(ctx.get("capital")).isEqualTo("Tbilisi");
    }

    @Test
    void shouldUseExtractedParamsInSubsequentPrompts() throws Exception {
        // step1 returns JSON; step2's prompt placeholders must resolve from extracted params.
        var extract =
                StandardNode.builder()
                        .id("extract")
                        .agentId("test-agent")
                        .prompt("Extract country and language info")
                        .outputParams(List.of("country", "language"))
                        .transitionRules(List.of(new SuccessTransition("use")))
                        .build();
        var use =
                StandardNode.builder()
                        .id("use")
                        .agentId("test-agent")
                        .prompt("Write an article about {country} in {language}")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("param-chain")
                        .agent(agentCfg())
                        .startNode(extract)
                        .node(use)
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                "{\"country\": \"Georgia\", \"language\": \"Georgian\"}"))
                .thenReturn(AgentResponse.TextResponse.of("Article about Georgia in Georgian"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var ctx = ((ExecutionResult.Completed) result).getFinalState().getContext();
        assertThat(ctx.get("country")).isEqualTo("Georgia");
        assertThat(ctx.get("language")).isEqualTo("Georgian");
    }

    // =========================================================================
    // Behavioral tests
    // =========================================================================

    @Nested
    class FailureRetryTest {

        @Test
        void shouldRetryOnFailureAndSucceedOnSecondAttempt() throws Exception {
            // FailureTransition(maxRetries=3) — agent fails once then succeeds → SUCCESS end.
            var start =
                    StandardNode.builder()
                            .id("start")
                            .agentId("test-agent")
                            .prompt("Do work")
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("end"),
                                            new FailureTransition(3, "fallback")))
                            .build();
            var workflow =
                    WorkflowTest.TestWorkflowBuilder.create("retry")
                            .agent(agentCfg())
                            .startNode(start)
                            .node(end("end"))
                            .node(failEnd("fallback"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.Error.of("Transient error"))
                    .thenReturn(AgentResponse.TextResponse.of("Success"));

            var result = executor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus())
                    .isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldExhaustRetriesAndTransitionToFallback() throws Exception {
            // 1 original + 3 retries all fail → FailureTransition fires → fallback (FAILURE).
            var start =
                    StandardNode.builder()
                            .id("start")
                            .agentId("test-agent")
                            .prompt("Do work")
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("end"),
                                            new FailureTransition(3, "fallback")))
                            .build();
            var workflow =
                    WorkflowTest.TestWorkflowBuilder.create("exhaust-retry")
                            .agent(agentCfg())
                            .startNode(start)
                            .node(end("end"))
                            .node(failEnd("fallback"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.Error.of("Persistent error"));

            var result = executor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus())
                    .isEqualTo(ExitStatus.FAILURE);
        }
    }

    @Nested
    class ScoreRoutingTest {

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
            // This tests a ScoreCondition type not covered by the simple threshold cases above.
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
                    .thenReturn(AgentResponse.TextResponse.of("OK work"));

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

    @Nested
    class RubricBacktrackTest {

        @Test
        void shouldAutoBacktrackOnMinorFailure() throws Exception {
            // score 75 (< 80 threshold) on first attempt → auto-backtrack → retry → score 90 →
            // SUCCESS.
            when(rubricEngine.exists("quality")).thenReturn(true);
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
            when(rubricEngine.exists("quality")).thenReturn(true);
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
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Output"));

            var result = executor.execute(workflow, new HashMap<>());

            // After exhausting retries the SuccessTransition fires → SUCCESS.
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus())
                    .isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldPreferOnScoreOverAutoBacktrack() throws Exception {
            // score 60 would trigger auto-backtrack, but ScoreTransition LT 70 → "revise"
            // takes precedence, routing to a FAILURE end node.
            when(rubricEngine.exists("quality")).thenReturn(true);
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
                    .thenReturn(AgentResponse.TextResponse.of("Mediocre output"));

            var result = executor.execute(workflow, new HashMap<>());

            // ScoreTransition LT 70 fires (not auto-backtrack) → "revise" → FAILURE.
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus())
                    .isEqualTo(ExitStatus.FAILURE);
        }
    }

    @Nested
    class StaleRubricTest {

        @Test
        void shouldNotLeakRubricScoreBetweenNodes() throws RubricNotFoundException {
            // node1 evaluates rubric (score 85); node2 has no rubricId but uses ScoreTransition.
            // If the score leaked, node2 would route "good". It must NOT — the transition
            // evaluator must clear the score between nodes and throw instead.
            when(rubricEngine.exists("quality")).thenReturn(true);
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
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Output"));

            assertThatThrownBy(() -> executor.execute(workflow, new HashMap<>()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No valid transition");
        }
    }

    @Nested
    class GenericNodeExecutionTest {

        private DefaultNodeExecutorRegistry genericRegistry;
        private WorkflowExecutor genericExecutor;

        @BeforeEach
        void setUpGenericExecutor() {
            genericRegistry = new DefaultNodeExecutorRegistry();
            genericExecutor =
                    new WorkflowExecutor(
                            genericRegistry,
                            agentRegistry,
                            executorService,
                            rubricEngine,
                            ReviewHandler.AUTO_APPROVE);
        }

        @Test
        void shouldExecuteGenericNodeViaHandler() throws Exception {
            // Handler reads config["target"] and returns it in the output.
            genericRegistry.registerGenericHandler(
                    "validator",
                    new GenericNodeHandler() {
                        @Override
                        public String getType() {
                            return "validator";
                        }

                        @Override
                        public NodeResult handle(GenericNode node, ExecutionContext context) {
                            return NodeResult.success(
                                    "Validated: " + node.getConfig().get("target"), Map.of());
                        }
                    });

            var nodes = new HashMap<String, Node>();
            nodes.put(
                    "validate",
                    GenericNode.builder()
                            .id("validate")
                            .executorType("validator")
                            .config(Map.of("target", "user-input"))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", end("end"));
            var workflow =
                    Workflow.builder()
                            .id("generic-test")
                            .nodes(nodes)
                            .startNode("validate")
                            .build();

            var result = genericExecutor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            var completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
            assertThat(completed.getFinalState().getContext().get("validate").toString())
                    .contains("Validated");
        }

        @Test
        void shouldRouteGenericNodeOnHandlerFailure() throws Exception {
            // Handler returns failure → FailureTransition fires → failure-end.
            genericRegistry.registerGenericHandler(
                    "validator",
                    new GenericNodeHandler() {
                        @Override
                        public String getType() {
                            return "validator";
                        }

                        @Override
                        public NodeResult handle(GenericNode node, ExecutionContext context) {
                            return NodeResult.failure("Validation failed");
                        }
                    });

            var nodes = new HashMap<String, Node>();
            nodes.put(
                    "validate",
                    GenericNode.builder()
                            .id("validate")
                            .executorType("validator")
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("success-end"),
                                            new FailureTransition(0, "failure-end")))
                            .build());
            nodes.put("success-end", end("success-end"));
            nodes.put("failure-end", failEnd("failure-end"));
            var workflow =
                    Workflow.builder()
                            .id("generic-fail")
                            .nodes(nodes)
                            .startNode("validate")
                            .build();

            var result = genericExecutor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus())
                    .isEqualTo(ExitStatus.FAILURE);
        }
    }

    @Nested
    class ActionNodeExecutionTest {

        private ActionExecutor mockActionExecutor;
        private WorkflowExecutor actionNodeExecutor;

        @BeforeEach
        void setUpActionExecutor() {
            mockActionExecutor = mock(ActionExecutor.class);
            actionNodeExecutor =
                    new WorkflowExecutor(
                            new DefaultNodeExecutorRegistry(),
                            agentRegistry,
                            executorService,
                            rubricEngine,
                            ReviewHandler.AUTO_APPROVE,
                            mockActionExecutor);
        }

        @Test
        void shouldExecuteActionNodeAndContinue() throws Exception {
            when(mockActionExecutor.execute(any(), any()))
                    .thenReturn(ActionExecutor.ActionResult.success("Action done"));

            var nodes = new HashMap<String, Node>();
            nodes.put(
                    "action",
                    ActionNode.builder()
                            .id("action")
                            .actions(List.of(new Action.Execute("git-commit")))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", end("end"));
            var workflow =
                    Workflow.builder().id("action-test").nodes(nodes).startNode("action").build();

            var result = actionNodeExecutor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus())
                    .isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldHandleActionFailure() throws Exception {
            when(mockActionExecutor.execute(any(), any()))
                    .thenReturn(ActionExecutor.ActionResult.failure("Action failed"));

            var nodes = new HashMap<String, Node>();
            nodes.put(
                    "action",
                    ActionNode.builder()
                            .id("action")
                            .actions(List.of(new Action.Execute("deploy")))
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("success-end"),
                                            new FailureTransition(0, "failure-end")))
                            .build());
            nodes.put("success-end", end("success-end"));
            nodes.put("failure-end", failEnd("failure-end"));
            var workflow =
                    Workflow.builder().id("action-fail").nodes(nodes).startNode("action").build();

            var result = actionNodeExecutor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus())
                    .isEqualTo(ExitStatus.FAILURE);
        }
    }

    @Nested
    class ExitStatusTest {

        @Test
        void shouldPropagateCancelExitStatus() throws Exception {
            var workflow =
                    WorkflowTest.TestWorkflowBuilder.create("cancel-test")
                            .agent(agentCfg())
                            .startNode(
                                    StandardNode.builder()
                                            .id("start")
                                            .agentId("test-agent")
                                            .prompt("Process")
                                            .transitionRules(
                                                    List.of(new SuccessTransition("cancel-end")))
                                            .build())
                            .node(
                                    EndNode.builder()
                                            .id("cancel-end")
                                            .status(ExitStatus.CANCEL)
                                            .build())
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

            var result = executor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus())
                    .isEqualTo(ExitStatus.CANCEL);
        }
    }

    @Nested
    class TemplateResolutionTest {

        @Test
        void shouldResolvePreviousNodeOutputAsPlaceholder() throws Exception {
            // step1 output stored under key "step1"; step2 uses {step1} in its prompt.
            var workflow =
                    WorkflowTest.TestWorkflowBuilder.create("template-chain")
                            .agent(agentCfg())
                            .startNode(
                                    StandardNode.builder()
                                            .id("step1")
                                            .agentId("test-agent")
                                            .prompt("Generate greeting")
                                            .transitionRules(
                                                    List.of(new SuccessTransition("step2")))
                                            .build())
                            .node(
                                    StandardNode.builder()
                                            .id("step2")
                                            .agentId("test-agent")
                                            .prompt("Write about {step1}")
                                            .transitionRules(List.of(new SuccessTransition("end")))
                                            .build())
                            .node(end("end"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Hello World"))
                    .thenReturn(AgentResponse.TextResponse.of("Article about Hello World"));

            var result = executor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(
                            ((ExecutionResult.Completed) result)
                                    .getFinalState()
                                    .getContext()
                                    .get("step1"))
                    .isEqualTo("Hello World");
        }

        @Test
        void shouldResolveMultipleSourcesInOnePrompt() throws Exception {
            // step2 prompt uses both {topic} from initial context and {step1} from previous node.
            // The mock matcher verifies the exact resolved string, catching any resolution bug.
            var workflow =
                    WorkflowTest.TestWorkflowBuilder.create("multi-source")
                            .agent(agentCfg())
                            .startNode(
                                    StandardNode.builder()
                                            .id("step1")
                                            .agentId("test-agent")
                                            .prompt("Research {topic}")
                                            .transitionRules(
                                                    List.of(new SuccessTransition("step2")))
                                            .build())
                            .node(
                                    StandardNode.builder()
                                            .id("step2")
                                            .agentId("test-agent")
                                            .prompt("Write about {topic} using research: {step1}")
                                            .transitionRules(List.of(new SuccessTransition("end")))
                                            .build())
                            .node(end("end"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(eq("Research AI"), any()))
                    .thenReturn(AgentResponse.TextResponse.of("AI research findings"));
            when(mockAgent.execute(
                            eq("Write about AI using research: AI research findings"), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Final article"));

            var ctx = new HashMap<String, Object>();
            ctx.put("topic", "AI");
            var result = executor.execute(workflow, ctx);

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(
                            ((ExecutionResult.Completed) result)
                                    .getFinalState()
                                    .getContext()
                                    .get("step1"))
                    .isEqualTo("AI research findings");
        }
    }

    @Nested
    class ExecuteFromTest {

        @Test
        void shouldResumeWorkflowFromSavedState() throws Exception {
            // Resume from step2 with a pre-populated saved state.
            // step1 must NOT execute — only step2 runs. History size proves it.
            var workflow =
                    WorkflowTest.TestWorkflowBuilder.create("resume-test")
                            .agent(agentCfg())
                            .startNode(step("step1", "step2"))
                            .node(step("step2", "end"))
                            .node(end("end"))
                            .build();

            var savedState =
                    new HensuState.Builder()
                            .executionId("saved-exec")
                            .workflowId("resume-test")
                            .currentNode("step2")
                            .context(new HashMap<>(Map.of("step1_result", "already computed")))
                            .history(new ExecutionHistory())
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("step2 result"));

            var result = executor.executeFrom(workflow, savedState);

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            var completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
            // Only step2 was executed — step1 was in the saved state already.
            assertThat(completed.getFinalState().getHistory().getSteps()).hasSize(1);
            assertThat(completed.getFinalState().getHistory().getSteps().getFirst().getNodeId())
                    .isEqualTo("step2");
        }
    }

    @Nested
    class ContextIsolationTest {

        @Test
        void shouldNotLeakContextBetweenSeparateExecutions() throws Exception {
            // If the executor reuses state between calls, "secret" from execution 1
            // would appear in the final state of execution 2.
            var workflow =
                    WorkflowTest.TestWorkflowBuilder.create("isolation-test")
                            .agent(agentCfg())
                            .startNode(step("start", "end"))
                            .node(end("end"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("output"));

            var ctx1 = new HashMap<String, Object>();
            ctx1.put("secret", "sensitive-data");
            var result1 = executor.execute(workflow, ctx1);
            var result2 = executor.execute(workflow, new HashMap<>());

            assertThat(result1).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(result2).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result2).getFinalState().getContext())
                    .doesNotContainKey("secret");
        }
    }

    @Nested
    class ParallelConsensusTest {

        @ParameterizedTest
        @MethodSource("majorityConsensusCases")
        void shouldRouteByMajorityVote(String r1, String r2, String r3, ExitStatus expected)
                throws Exception {
            var agent1 = mock(Agent.class);
            var agent2 = mock(Agent.class);
            var agent3 = mock(Agent.class);
            when(agentRegistry.getAgent("reviewer1")).thenReturn(Optional.of(agent1));
            when(agentRegistry.getAgent("reviewer2")).thenReturn(Optional.of(agent2));
            when(agentRegistry.getAgent("reviewer3")).thenReturn(Optional.of(agent3));
            when(agent1.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of(r1));
            when(agent2.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of(r2));
            when(agent3.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of(r3));

            var agents =
                    Map.of(
                            "reviewer1",
                            AgentConfig.builder()
                                    .id("reviewer1")
                                    .role("Reviewer")
                                    .model("test")
                                    .build(),
                            "reviewer2",
                            AgentConfig.builder()
                                    .id("reviewer2")
                                    .role("Reviewer")
                                    .model("test")
                                    .build(),
                            "reviewer3",
                            AgentConfig.builder()
                                    .id("reviewer3")
                                    .role("Reviewer")
                                    .model("test")
                                    .build());
            var nodes = new HashMap<String, Node>();
            nodes.put(
                    "parallel",
                    ParallelNode.builder("parallel")
                            .branch("b1", "reviewer1", "Review this work")
                            .branch("b2", "reviewer2", "Review this work")
                            .branch("b3", "reviewer3", "Review this work")
                            .consensus(null, ConsensusStrategy.MAJORITY_VOTE)
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("success-end"),
                                            new FailureTransition(0, "failure-end")))
                            .build());
            nodes.put("success-end", end("success-end"));
            nodes.put("failure-end", failEnd("failure-end"));
            var workflow =
                    Workflow.builder()
                            .id("majority-vote")
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("parallel")
                            .build();

            var result = executor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus()).isEqualTo(expected);
        }

        static Stream<Arguments> majorityConsensusCases() {
            return Stream.of(
                    // 2 approve / 1 reject → majority approves → SUCCESS
                    Arguments.of(
                            "I approve this work. Score: 90",
                            "I approve. Score: 85",
                            "I reject this. Score: 30",
                            ExitStatus.SUCCESS),
                    // 1 approve / 2 reject → majority rejects → FAILURE
                    Arguments.of(
                            "I approve. Score: 90",
                            "I reject this. Score: 20",
                            "I reject this. Score: 15",
                            ExitStatus.FAILURE));
        }

        @Test
        void shouldCollectBranchOutputsWithoutConsensus() throws Exception {
            // No consensus config → all branch outputs collected → always SUCCESS.
            var agent1 = mock(Agent.class);
            var agent2 = mock(Agent.class);
            when(agentRegistry.getAgent("writer1")).thenReturn(Optional.of(agent1));
            when(agentRegistry.getAgent("writer2")).thenReturn(Optional.of(agent2));
            when(agent1.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Draft from writer 1"));
            when(agent2.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Draft from writer 2"));

            var agents =
                    Map.of(
                            "writer1",
                                    AgentConfig.builder()
                                            .id("writer1")
                                            .role("Writer")
                                            .model("test")
                                            .build(),
                            "writer2",
                                    AgentConfig.builder()
                                            .id("writer2")
                                            .role("Writer")
                                            .model("test")
                                            .build());
            var nodes = new HashMap<String, Node>();
            nodes.put(
                    "parallel",
                    ParallelNode.builder("parallel")
                            .branch("b1", "writer1", "Write draft")
                            .branch("b2", "writer2", "Write draft")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", end("end"));
            var workflow =
                    Workflow.builder()
                            .id("no-consensus")
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("parallel")
                            .build();

            var result = executor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus())
                    .isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldEvaluateBranchRubricInConsensus() throws Exception {
            // 3 branches with rubricId; r1/r2 pass (90/85), r3 fails (40).
            // 2/3 pass → MAJORITY_VOTE → consensus reached → SUCCESS.
            var agent1 = mock(Agent.class);
            var agent2 = mock(Agent.class);
            var agent3 = mock(Agent.class);
            when(agentRegistry.getAgent("r1")).thenReturn(Optional.of(agent1));
            when(agentRegistry.getAgent("r2")).thenReturn(Optional.of(agent2));
            when(agentRegistry.getAgent("r3")).thenReturn(Optional.of(agent3));
            when(agent1.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Good output"));
            when(agent2.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Great output"));
            when(agent3.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Poor output"));
            when(rubricEngine.exists("quality")).thenReturn(true);
            when(rubricEngine.evaluate(eq("quality"), any(), any()))
                    .thenReturn(
                            RubricEvaluation.builder()
                                    .rubricId("quality")
                                    .score(90.0)
                                    .passed(true)
                                    .build())
                    .thenReturn(
                            RubricEvaluation.builder()
                                    .rubricId("quality")
                                    .score(85.0)
                                    .passed(true)
                                    .build())
                    .thenReturn(
                            RubricEvaluation.builder()
                                    .rubricId("quality")
                                    .score(40.0)
                                    .passed(false)
                                    .build());

            var agents =
                    Map.of(
                            "r1",
                            AgentConfig.builder().id("r1").role("Reviewer").model("test").build(),
                            "r2",
                            AgentConfig.builder().id("r2").role("Reviewer").model("test").build(),
                            "r3",
                            AgentConfig.builder().id("r3").role("Reviewer").model("test").build());
            var nodes = new HashMap<String, Node>();
            nodes.put(
                    "parallel",
                    ParallelNode.builder("parallel")
                            .branch("b1", "r1", "Review", "quality")
                            .branch("b2", "r2", "Review", "quality")
                            .branch("b3", "r3", "Review", "quality")
                            .consensus(null, ConsensusStrategy.MAJORITY_VOTE)
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("success-end"),
                                            new FailureTransition(0, "failure-end")))
                            .build());
            nodes.put("success-end", end("success-end"));
            nodes.put("failure-end", failEnd("failure-end"));
            var workflow =
                    Workflow.builder()
                            .id("rubric-consensus")
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("parallel")
                            .rubrics(Map.of("quality", "test-path"))
                            .build();

            var result = executor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus())
                    .isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldSkipRubricWhenBranchHasNoRubricId() throws Exception {
            // Branches without rubricId fall back to keyword heuristics.
            // "approve" keyword → APPROVE for both → UNANIMOUS → SUCCESS.
            var agent1 = mock(Agent.class);
            var agent2 = mock(Agent.class);
            when(agentRegistry.getAgent("r1")).thenReturn(Optional.of(agent1));
            when(agentRegistry.getAgent("r2")).thenReturn(Optional.of(agent2));
            when(agent1.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I approve this"));
            when(agent2.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I approve this"));

            var agents =
                    Map.of(
                            "r1",
                            AgentConfig.builder().id("r1").role("Reviewer").model("test").build(),
                            "r2",
                            AgentConfig.builder().id("r2").role("Reviewer").model("test").build());
            var nodes = new HashMap<String, Node>();
            nodes.put(
                    "parallel",
                    ParallelNode.builder("parallel")
                            .branch("b1", "r1", "Review")
                            .branch("b2", "r2", "Review")
                            .consensus(null, ConsensusStrategy.UNANIMOUS)
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("success-end"),
                                            new FailureTransition(0, "failure-end")))
                            .build());
            nodes.put("success-end", end("success-end"));
            nodes.put("failure-end", failEnd("failure-end"));
            var workflow =
                    Workflow.builder()
                            .id("no-rubric-consensus")
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("parallel")
                            .build();

            var result = executor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus())
                    .isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldHandleRubricEvaluationFailureGracefully() throws Exception {
            // rubricEngine throws RubricNotFoundException for branch evaluation.
            // Executor must fall back to keyword heuristics rather than propagating the error.
            var agent1 = mock(Agent.class);
            var agent2 = mock(Agent.class);
            when(agentRegistry.getAgent("r1")).thenReturn(Optional.of(agent1));
            when(agentRegistry.getAgent("r2")).thenReturn(Optional.of(agent2));
            when(agent1.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I approve this"));
            when(agent2.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I approve this"));
            when(rubricEngine.exists("quality")).thenReturn(true);
            when(rubricEngine.evaluate(eq("quality"), any(), any()))
                    .thenThrow(new RubricNotFoundException("Rubric not found: quality"));

            var agents =
                    Map.of(
                            "r1",
                            AgentConfig.builder().id("r1").role("Reviewer").model("test").build(),
                            "r2",
                            AgentConfig.builder().id("r2").role("Reviewer").model("test").build());
            var nodes = new HashMap<String, Node>();
            nodes.put(
                    "parallel",
                    ParallelNode.builder("parallel")
                            .branch("b1", "r1", "Review", "quality")
                            .branch("b2", "r2", "Review", "quality")
                            .consensus(null, ConsensusStrategy.UNANIMOUS)
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("success-end"),
                                            new FailureTransition(0, "failure-end")))
                            .build());
            nodes.put("success-end", end("success-end"));
            nodes.put("failure-end", failEnd("failure-end"));
            var workflow =
                    Workflow.builder()
                            .id("rubric-failure-fallback")
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("parallel")
                            .rubrics(Map.of("quality", "test-path"))
                            .build();

            // rubric fails → keyword "approve" fallback → unanimous → SUCCESS
            var result = executor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus())
                    .isEqualTo(ExitStatus.SUCCESS);
        }
    }

    @Nested
    class HumanReviewTest {

        private ReviewHandler mockReviewHandler;
        private WorkflowExecutor reviewExecutor;

        @BeforeEach
        void setUpReviewExecutor() {
            mockReviewHandler = mock(ReviewHandler.class);
            reviewExecutor =
                    new WorkflowExecutor(
                            new DefaultNodeExecutorRegistry(),
                            agentRegistry,
                            executorService,
                            rubricEngine,
                            mockReviewHandler);
        }

        @Test
        void shouldAutoApproveWhenReviewDisabled() throws Exception {
            var workflow =
                    WorkflowTest.TestWorkflowBuilder.create("review-disabled")
                            .agent(agentCfg())
                            .startNode(
                                    StandardNode.builder()
                                            .id("step")
                                            .agentId("test-agent")
                                            .prompt("Work")
                                            .reviewConfig(
                                                    new ReviewConfig(
                                                            ReviewMode.DISABLED, false, false))
                                            .transitionRules(List.of(new SuccessTransition("end")))
                                            .build())
                            .node(end("end"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

            var result = reviewExecutor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            verify(mockReviewHandler, never())
                    .requestReview(any(), any(), any(), any(), any(), any());
        }

        @Test
        void shouldAutoApproveSuccessWhenReviewOptional() throws Exception {
            // OPTIONAL + agent SUCCESS → reviewHandler never called.
            var workflow =
                    WorkflowTest.TestWorkflowBuilder.create("review-optional-success")
                            .agent(agentCfg())
                            .startNode(
                                    StandardNode.builder()
                                            .id("step")
                                            .agentId("test-agent")
                                            .prompt("Work")
                                            .reviewConfig(
                                                    new ReviewConfig(
                                                            ReviewMode.OPTIONAL, false, false))
                                            .transitionRules(List.of(new SuccessTransition("end")))
                                            .build())
                            .node(end("end"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

            var result = reviewExecutor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            verify(mockReviewHandler, never())
                    .requestReview(any(), any(), any(), any(), any(), any());
        }

        @Test
        void shouldAlwaysRequestReviewWhenRequired() throws Exception {
            when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ReviewDecision.Approve(null));

            var workflow =
                    WorkflowTest.TestWorkflowBuilder.create("review-required")
                            .agent(agentCfg())
                            .startNode(
                                    StandardNode.builder()
                                            .id("step")
                                            .agentId("test-agent")
                                            .prompt("Work")
                                            .reviewConfig(
                                                    new ReviewConfig(
                                                            ReviewMode.REQUIRED, true, true))
                                            .transitionRules(List.of(new SuccessTransition("end")))
                                            .build())
                            .node(end("end"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

            var result = reviewExecutor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            // REQUIRED → reviewHandler must be called even on success.
            verify(mockReviewHandler).requestReview(any(), any(), any(), any(), any(), any());
        }

        @Test
        void shouldRejectWorkflowOnReviewRejection() throws Exception {
            when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ReviewDecision.Reject("Quality insufficient"));

            var workflow =
                    WorkflowTest.TestWorkflowBuilder.create("review-reject")
                            .agent(agentCfg())
                            .startNode(
                                    StandardNode.builder()
                                            .id("step")
                                            .agentId("test-agent")
                                            .prompt("Work")
                                            .reviewConfig(
                                                    new ReviewConfig(
                                                            ReviewMode.REQUIRED, true, true))
                                            .transitionRules(List.of(new SuccessTransition("end")))
                                            .build())
                            .node(end("end"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

            var result = reviewExecutor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Rejected.class);
            assertThat(((ExecutionResult.Rejected) result).getReason())
                    .isEqualTo("Quality insufficient");
        }

        @Test
        void shouldBacktrackOnReviewBacktrack() throws Exception {
            // First review backtracks to step1; second approves. step1 executes twice → 4+ history
            // entries.
            when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenAnswer(
                            invocation -> {
                                HensuState state = invocation.getArgument(2);
                                return new ReviewDecision.Backtrack("step1", state, "Redo step 1");
                            })
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
                                                    new ReviewConfig(
                                                            ReviewMode.REQUIRED, true, true))
                                            .transitionRules(List.of(new SuccessTransition("end")))
                                            .build())
                            .node(end("end"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Output"));

            var result = reviewExecutor.execute(workflow, new HashMap<>());

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
    }

    @Nested
    class ForkJoinTest {

        @Test
        void shouldForkTargetsAndJoinWithCollectAll() throws Exception {
            // Fork spawns taskA + taskB; join merges with COLLECT_ALL → result stored in context.
            var agentA = mock(Agent.class);
            var agentB = mock(Agent.class);
            when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
            when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
            when(agentA.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Result A"));
            when(agentB.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Result B"));

            var agents =
                    Map.of(
                            "agent-a",
                            AgentConfig.builder()
                                    .id("agent-a")
                                    .role("Worker A")
                                    .model("test")
                                    .build(),
                            "agent-b",
                            AgentConfig.builder()
                                    .id("agent-b")
                                    .role("Worker B")
                                    .model("test")
                                    .build());
            var nodes = new HashMap<String, Node>();
            nodes.put(
                    "taskA",
                    StandardNode.builder()
                            .id("taskA")
                            .agentId("agent-a")
                            .prompt("Do task A")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put(
                    "taskB",
                    StandardNode.builder()
                            .id("taskB")
                            .agentId("agent-b")
                            .prompt("Do task B")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put(
                    "fork1",
                    ForkNode.builder("fork1")
                            .targets("taskA", "taskB")
                            .transitionRules(List.of(new SuccessTransition("join1")))
                            .build());
            nodes.put(
                    "join1",
                    JoinNode.builder("join1")
                            .awaitTargets("fork1")
                            .mergeStrategy(MergeStrategy.COLLECT_ALL)
                            .outputField("fork_results")
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("end"),
                                            new FailureTransition(0, "fail-end")))
                            .build());
            nodes.put("end", end("end"));
            nodes.put("fail-end", failEnd("fail-end"));
            var workflow =
                    Workflow.builder()
                            .id("fork-join-test")
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("fork1")
                            .build();

            var result = executor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            var completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
            assertThat(completed.getFinalState().getContext()).containsKey("fork_results");
        }

        @ParameterizedTest(name = "failOnAnyError={0} → {1}")
        @MethodSource("failOnAnyErrorCases")
        void shouldHandleJoinFailOnAnyError(boolean failOnAnyError, ExitStatus expected)
                throws Exception {
            // taskA succeeds, taskB always fails. Outcome depends on failOnAnyError flag.
            var agentA = mock(Agent.class);
            var agentB = mock(Agent.class);
            when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
            when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
            when(agentA.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Result A"));
            when(agentB.execute(any(), any())).thenReturn(AgentResponse.Error.of("Task B failed"));

            var agents =
                    Map.of(
                            "agent-a",
                            AgentConfig.builder()
                                    .id("agent-a")
                                    .role("Worker A")
                                    .model("test")
                                    .build(),
                            "agent-b",
                            AgentConfig.builder()
                                    .id("agent-b")
                                    .role("Worker B")
                                    .model("test")
                                    .build());
            var nodes = new HashMap<String, Node>();
            nodes.put(
                    "taskA",
                    StandardNode.builder()
                            .id("taskA")
                            .agentId("agent-a")
                            .prompt("Do task A")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put(
                    "taskB",
                    StandardNode.builder()
                            .id("taskB")
                            .agentId("agent-b")
                            .prompt("Do task B")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put(
                    "fork1",
                    ForkNode.builder("fork1")
                            .targets("taskA", "taskB")
                            .transitionRules(List.of(new SuccessTransition("join1")))
                            .build());
            nodes.put(
                    "join1",
                    JoinNode.builder("join1")
                            .awaitTargets("fork1")
                            .mergeStrategy(MergeStrategy.COLLECT_ALL)
                            .failOnAnyError(failOnAnyError)
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("end"),
                                            new FailureTransition(0, "fail-end")))
                            .build());
            nodes.put("end", end("end"));
            nodes.put("fail-end", failEnd("fail-end"));
            var workflow =
                    Workflow.builder()
                            .id("fork-join-fail-test")
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("fork1")
                            .build();

            var result = executor.execute(workflow, new HashMap<>());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).getExitStatus()).isEqualTo(expected);
        }

        static Stream<Arguments> failOnAnyErrorCases() {
            return Stream.of(
                    Arguments.of(
                            true, ExitStatus.FAILURE), // taskB fails + failOnAnyError → FAILURE
                    Arguments.of(false, ExitStatus.SUCCESS) // taskB fails but ignored → SUCCESS
                    );
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /// Creates an AgentConfig for the default "test-agent" used by {@link #step}.
    private static AgentConfig agentCfg() {
        return AgentConfig.builder().id("test-agent").role("Test").model("test").build();
    }

    /// Creates a StandardNode with agentId="test-agent", default prompt, and a single
    /// SuccessTransition.
    private static Node step(String id, String next) {
        return StandardNode.builder()
                .id(id)
                .agentId("test-agent")
                .prompt("Do work")
                .transitionRules(List.of(new SuccessTransition(next)))
                .build();
    }

    /// Creates an EndNode with ExitStatus.SUCCESS.
    private static Node end(String id) {
        return EndNode.builder().id(id).status(ExitStatus.SUCCESS).build();
    }

    /// Creates an EndNode with ExitStatus.FAILURE.
    private static Node failEnd(String id) {
        return EndNode.builder().id(id).status(ExitStatus.FAILURE).build();
    }
}
