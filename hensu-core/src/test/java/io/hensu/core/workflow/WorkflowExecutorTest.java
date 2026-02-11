package io.hensu.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.parallel.ConsensusStrategy;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        ReviewHandler reviewHandler = ReviewHandler.AUTO_APPROVE;
        NodeExecutorRegistry nodeExecutorRegistry = new DefaultNodeExecutorRegistry();

        executor =
                new WorkflowExecutor(
                        nodeExecutorRegistry,
                        agentRegistry,
                        executorService,
                        rubricEngine,
                        reviewHandler);
    }

    @AfterEach
    void tearDown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    @Test
    void shouldExecuteSimpleWorkflowToEnd() throws Exception {
        // Given
        Workflow workflow = createSimpleWorkflow();
        Map<String, Object> context = new HashMap<>();
        context.put("input", "test data");

        AgentResponse successResponse = AgentResponse.TextResponse.of("Agent output");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(successResponse);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void shouldThrowExceptionWhenNodeNotFound() {
        // Given
        Workflow workflow = createWorkflowWithMissingNode();
        Map<String, Object> context = new HashMap<>();

        AgentResponse successResponse = AgentResponse.TextResponse.of("Agent output");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(successResponse);

        // When/Then
        assertThatThrownBy(() -> executor.execute(workflow, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Node not found");
    }

    @Test
    void shouldThrowExceptionWhenAgentNotFound() {
        // Given
        Workflow workflow = createSimpleWorkflow();
        Map<String, Object> context = new HashMap<>();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> executor.execute(workflow, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent not found");
    }

    @Test
    void shouldResolveTemplateVariables() throws Exception {
        // Given
        Workflow workflow = createWorkflowWithTemplatePrompt();
        Map<String, Object> context = new HashMap<>();
        context.put("topic", "artificial intelligence");
        context.put("style", "formal");

        AgentResponse successResponse = AgentResponse.TextResponse.of("Generated content");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(eq("Write about artificial intelligence in formal style"), any()))
                .thenReturn(successResponse);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    void shouldRecordExecutionHistory() throws Exception {
        // Given
        Workflow workflow = createMultiStepWorkflow();
        Map<String, Object> context = new HashMap<>();

        AgentResponse successResponse = AgentResponse.TextResponse.of("Agent output");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(successResponse);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
        assertThat(completed.getFinalState().getHistory().getSteps()).hasSize(2);
    }

    @Test
    void shouldHandleAgentFailureResponse() throws Exception {
        // Given
        Workflow workflow = createWorkflowWithFailureTransition();
        Map<String, Object> context = new HashMap<>();

        AgentResponse failureResponse = AgentResponse.Error.of("Agent error");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(failureResponse);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.FAILURE);
    }

    @Test
    void shouldExecuteToFailureEnd() throws Exception {
        // Given
        Workflow workflow = createWorkflowWithFailureEnd();
        Map<String, Object> context = new HashMap<>();

        AgentResponse successResponse = AgentResponse.TextResponse.of("Agent output");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(successResponse);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.FAILURE);
    }

    @Test
    void shouldThrowWhenNoValidTransition() {
        // Given
        Workflow workflow = createWorkflowWithNoTransition();
        Map<String, Object> context = new HashMap<>();

        AgentResponse successResponse = AgentResponse.TextResponse.of("Agent output");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(successResponse);

        // When/Then
        assertThatThrownBy(() -> executor.execute(workflow, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No valid transition");
    }

    @Test
    void shouldHandleEmptyPrompt() throws Exception {
        // Given
        Workflow workflow = createWorkflowWithNullPrompt();
        Map<String, Object> context = new HashMap<>();

        AgentResponse successResponse = AgentResponse.TextResponse.of("Agent output");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(eq(""), any())).thenReturn(successResponse);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    void shouldExtractParametersFromJsonOutput() throws Exception {
        // Given: Workflow where step1 outputs JSON with parameters, step2 uses them
        Workflow workflow = createWorkflowWithOutputParams();
        Map<String, Object> context = new HashMap<>();

        // Step 1: Returns JSON with extractable parameters
        String jsonOutput =
                """
            Here are the facts:
            {
                "lake_name": "Lake Paravani",
                "peak_height": "5201m",
                "capital": "Tbilisi"
            }
            """;
        AgentResponse step1Response = AgentResponse.TextResponse.of(jsonOutput);

        // Step 2: Uses extracted parameters
        AgentResponse step2Response =
                AgentResponse.TextResponse.of("Final output using extracted params");

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(step1Response).thenReturn(step2Response);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;

        // Verify parameters were extracted and stored in context
        Map<String, Object> finalContext = completed.getFinalState().getContext();
        assertThat(finalContext.get("lake_name")).isEqualTo("Lake Paravani");
        assertThat(finalContext.get("peak_height")).isEqualTo("5201m");
        assertThat(finalContext.get("capital")).isEqualTo("Tbilisi");
    }

    @Test
    void shouldUseExtractedParamsInSubsequentPrompts() throws Exception {
        // Given: Workflow where step2's prompt uses placeholders from step1's output
        Workflow workflow = createWorkflowWithParamPlaceholders();
        Map<String, Object> context = new HashMap<>();

        // Step 1: Returns JSON with parameters
        String jsonOutput =
                """
            {"country": "Georgia", "language": "Georgian"}
            """;
        AgentResponse step1Response = AgentResponse.TextResponse.of(jsonOutput);
        AgentResponse step2Response =
                AgentResponse.TextResponse.of("Article about Georgia in Georgian");

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(step1Response).thenReturn(step2Response);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);

        // Verify the second agent call received resolved placeholders
        // Note: We verify by checking the context contains the extracted params
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
        assertThat(completed.getFinalState().getContext().get("country")).isEqualTo("Georgia");
        assertThat(completed.getFinalState().getContext().get("language")).isEqualTo("Georgian");
    }

    // ===== Behavioral Tests =====

    @Nested
    class FailureRetryTest {

        @Test
        void shouldRetryOnFailureAndSucceedOnSecondAttempt() throws Exception {
            // Given: workflow with retry-3 failure transition
            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "start",
                    StandardNode.builder()
                            .id("start")
                            .agentId("test-agent")
                            .prompt("Do work")
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("end"),
                                            new FailureTransition(3, "fallback")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());
            nodes.put(
                    "fallback",
                    EndNode.builder().id("fallback").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("retry-workflow")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "retry-workflow",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("start")
                            .build();

            AgentResponse failResponse = AgentResponse.Error.of("Transient error");
            AgentResponse successResponse = AgentResponse.TextResponse.of("Success");
            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(failResponse)
                    .thenReturn(successResponse);

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: agent failed once, succeeded on retry → SUCCESS end
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldExhaustRetriesAndTransitionToFallback() throws Exception {
            // Given: workflow with retry-3 failure transition, agent always fails
            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "start",
                    StandardNode.builder()
                            .id("start")
                            .agentId("test-agent")
                            .prompt("Do work")
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("end"),
                                            new FailureTransition(3, "fallback")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());
            nodes.put(
                    "fallback",
                    EndNode.builder().id("fallback").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("exhaust-retry")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "exhaust-retry",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("start")
                            .build();

            AgentResponse failResponse = AgentResponse.Error.of("Persistent error");
            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any())).thenReturn(failResponse);

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: 1 initial + 3 retries exhausted → fallback (FAILURE)
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.FAILURE);
        }
    }

    @Nested
    class ScoreRoutingTest {

        @Test
        void shouldRouteToHighScoreTarget() throws Exception {
            // Given: rubric passes with score 90, ScoreTransition routes GTE 80 → excellent
            when(rubricEngine.exists("quality")).thenReturn(true);
            when(rubricEngine.evaluate(eq("quality"), any(), any()))
                    .thenReturn(
                            RubricEvaluation.builder()
                                    .rubricId("quality")
                                    .score(90.0)
                                    .passed(true)
                                    .build());

            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "review",
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
                            .build());
            nodes.put(
                    "excellent",
                    EndNode.builder().id("excellent").status(ExitStatus.SUCCESS).build());
            nodes.put("poor", EndNode.builder().id("poor").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("score-route")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "score-route",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("review")
                            .rubrics(Map.of("quality", "test-path"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Good work"));

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldRouteToLowScoreTarget() throws Exception {
            // Given: rubric fails with score 40, ScoreTransition routes LT 80 → poor
            when(rubricEngine.exists("quality")).thenReturn(true);
            when(rubricEngine.evaluate(eq("quality"), any(), any()))
                    .thenReturn(
                            RubricEvaluation.builder()
                                    .rubricId("quality")
                                    .score(40.0)
                                    .passed(false)
                                    .build());

            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "review",
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
                            .build());
            nodes.put(
                    "excellent",
                    EndNode.builder().id("excellent").status(ExitStatus.SUCCESS).build());
            nodes.put("poor", EndNode.builder().id("poor").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("score-low")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "score-low",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("review")
                            .rubrics(Map.of("quality", "test-path"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Bad work"));

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: ScoreTransition takes precedence over auto-backtrack, routes to "poor"
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.FAILURE);
        }

        @Test
        void shouldRouteByScoreRange() throws Exception {
            // Given: score 75, RANGE 70..89 → "good"
            when(rubricEngine.exists("quality")).thenReturn(true);
            when(rubricEngine.evaluate(eq("quality"), any(), any()))
                    .thenReturn(
                            RubricEvaluation.builder()
                                    .rubricId("quality")
                                    .score(75.0)
                                    .passed(false)
                                    .build());

            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "review",
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
                            .build());
            nodes.put(
                    "excellent",
                    EndNode.builder().id("excellent").status(ExitStatus.SUCCESS).build());
            nodes.put("good", EndNode.builder().id("good").status(ExitStatus.SUCCESS).build());
            nodes.put("poor", EndNode.builder().id("poor").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("score-range")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "score-range",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("review")
                            .rubrics(Map.of("quality", "test-path"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("OK work"));

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: score 75 matches range 70..89 → routes to "good"
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldThrowWhenNoScoreConditionMatches() throws RubricNotFoundException {
            // Given: rubric passes (score 85), but ScoreTransition only matches GTE 90
            when(rubricEngine.exists("quality")).thenReturn(true);
            when(rubricEngine.evaluate(eq("quality"), any(), any()))
                    .thenReturn(
                            RubricEvaluation.builder()
                                    .rubricId("quality")
                                    .score(85.0)
                                    .passed(true)
                                    .build());

            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "review",
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
                            .build());
            nodes.put(
                    "excellent",
                    EndNode.builder().id("excellent").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("score-nomatch")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "score-nomatch",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("review")
                            .rubrics(Map.of("quality", "test-path"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Good"));

            // When/Then: score 85 < 90, rubric passed so no auto-backtrack → "No valid transition"
            assertThatThrownBy(() -> executor.execute(workflow, new HashMap<>()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No valid transition");
        }
    }

    @Nested
    class RubricBacktrackTest {

        @Test
        void shouldAutoBacktrackOnMinorFailure() throws Exception {
            // Given: score 75 first attempt (minor failure), score 90 on retry
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

            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "work",
                    StandardNode.builder()
                            .id("work")
                            .agentId("test-agent")
                            .prompt("Do work")
                            .rubricId("quality")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("backtrack-minor")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "backtrack-minor",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("work")
                            .rubrics(Map.of("quality", "test-path"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("First attempt"))
                    .thenReturn(AgentResponse.TextResponse.of("Improved attempt"));

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: auto-backtracked once (score 75 < 80), succeeded on retry → SUCCESS
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldStopBacktrackAfterMaxRetries() throws Exception {
            // Given: rubric always returns score 75 (minor failure, < 80)
            when(rubricEngine.exists("quality")).thenReturn(true);
            when(rubricEngine.evaluate(eq("quality"), any(), any()))
                    .thenReturn(
                            RubricEvaluation.builder()
                                    .rubricId("quality")
                                    .score(75.0)
                                    .passed(false)
                                    .build());

            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "work",
                    StandardNode.builder()
                            .id("work")
                            .agentId("test-agent")
                            .prompt("Do work")
                            .rubricId("quality")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("backtrack-exhaust")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "backtrack-exhaust",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("work")
                            .rubrics(Map.of("quality", "test-path"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Output"));

            // When: 1 original + 3 retries, then auto-backtrack gives up, SuccessTransition fires
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: after exhausting 3 backtrack retries, normal transitions take over → SUCCESS
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldPreferOnScoreOverAutoBacktrack() throws Exception {
            // Given: rubric score 60 (would trigger moderate auto-backtrack),
            //        but ScoreTransition LT 70 → "revise" takes precedence
            when(rubricEngine.exists("quality")).thenReturn(true);
            when(rubricEngine.evaluate(eq("quality"), any(), any()))
                    .thenReturn(
                            RubricEvaluation.builder()
                                    .rubricId("quality")
                                    .score(60.0)
                                    .passed(false)
                                    .build());

            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "review",
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
                            .build());
            nodes.put("revise", EndNode.builder().id("revise").status(ExitStatus.FAILURE).build());
            nodes.put(
                    "success-end",
                    EndNode.builder().id("success-end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("score-precedence")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "score-precedence",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("review")
                            .rubrics(Map.of("quality", "test-path"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Mediocre"));

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: ScoreTransition LT 70 matches (60 < 70) → routes to "revise", NOT
            // auto-backtrack
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.FAILURE);
        }
    }

    @Nested
    class StaleRubricTest {

        @Test
        void shouldNotLeakRubricScoreBetweenNodes() throws RubricNotFoundException {
            // Given: node1 has rubric (score 85, passed), node2 has ScoreTransition but no rubric
            when(rubricEngine.exists("quality")).thenReturn(true);
            when(rubricEngine.evaluate(eq("quality"), any(), any()))
                    .thenReturn(
                            RubricEvaluation.builder()
                                    .rubricId("quality")
                                    .score(85.0)
                                    .passed(true)
                                    .build());

            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "node1",
                    StandardNode.builder()
                            .id("node1")
                            .agentId("test-agent")
                            .prompt("Step 1")
                            .rubricId("quality")
                            .transitionRules(List.of(new SuccessTransition("node2")))
                            .build());
            // node2: NO rubric, but has ScoreTransition → should fail because no score available
            nodes.put(
                    "node2",
                    StandardNode.builder()
                            .id("node2")
                            .agentId("test-agent")
                            .prompt("Step 2")
                            .transitionRules(
                                    List.of(
                                            new ScoreTransition(
                                                    List.of(
                                                            new ScoreCondition(
                                                                    ComparisonOperator.GTE,
                                                                    80.0,
                                                                    null,
                                                                    "good")))))
                            .build());
            nodes.put("good", EndNode.builder().id("good").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("stale-rubric")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "stale-rubric",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("node1")
                            .rubrics(Map.of("quality", "test-path"))
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Output"));

            // When/Then: rubric eval cleared between nodes, node2's ScoreTransition finds no score
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
            // Given: register a handler that returns success
            genericRegistry.registerGenericHandler(
                    "validator",
                    new GenericNodeHandler() {
                        @Override
                        public String getType() {
                            return "validator";
                        }

                        @Override
                        public NodeResult handle(
                                GenericNode node,
                                io.hensu.core.execution.executor.ExecutionContext context) {
                            return NodeResult.success(
                                    "Validated: " + node.getConfig().get("target"), Map.of());
                        }
                    });

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "validate",
                    GenericNode.builder()
                            .id("validate")
                            .executorType("validator")
                            .config(Map.of("target", "user-input"))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("generic-test")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "generic-test",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .nodes(nodes)
                            .startNode("validate")
                            .build();

            // When
            ExecutionResult result = genericExecutor.execute(workflow, new HashMap<>());

            // Then
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
            assertThat(completed.getFinalState().getContext().get("validate").toString())
                    .contains("Validated");
        }

        @Test
        void shouldRouteGenericNodeOnHandlerFailure() throws Exception {
            // Given: handler returns failure
            genericRegistry.registerGenericHandler(
                    "validator",
                    new GenericNodeHandler() {
                        @Override
                        public String getType() {
                            return "validator";
                        }

                        @Override
                        public NodeResult handle(
                                GenericNode node,
                                io.hensu.core.execution.executor.ExecutionContext context) {
                            return NodeResult.failure("Validation failed");
                        }
                    });

            Map<String, Node> nodes = new HashMap<>();
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
            nodes.put(
                    "success-end",
                    EndNode.builder().id("success-end").status(ExitStatus.SUCCESS).build());
            nodes.put(
                    "failure-end",
                    EndNode.builder().id("failure-end").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("generic-fail")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "generic-fail",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .nodes(nodes)
                            .startNode("validate")
                            .build();

            // When
            ExecutionResult result = genericExecutor.execute(workflow, new HashMap<>());

            // Then: handler failure → FailureTransition → failure-end
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.FAILURE);
        }
    }

    @Nested
    class ActionNodeExecutionTest {

        private ActionExecutor mockActionExecutor;
        private WorkflowExecutor actionNodeExecutor;

        @BeforeEach
        void setUpActionExecutor() {
            mockActionExecutor = org.mockito.Mockito.mock(ActionExecutor.class);
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
            // Given
            when(mockActionExecutor.execute(any(), any()))
                    .thenReturn(ActionExecutor.ActionResult.success("Action done"));

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "action",
                    ActionNode.builder()
                            .id("action")
                            .actions(List.of(new Action.Execute("git-commit")))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("action-test")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "action-test",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .nodes(nodes)
                            .startNode("action")
                            .build();

            // When
            ExecutionResult result = actionNodeExecutor.execute(workflow, new HashMap<>());

            // Then
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldHandleActionFailure() throws Exception {
            // Given
            when(mockActionExecutor.execute(any(), any()))
                    .thenReturn(ActionExecutor.ActionResult.failure("Action failed"));

            Map<String, Node> nodes = new HashMap<>();
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
            nodes.put(
                    "success-end",
                    EndNode.builder().id("success-end").status(ExitStatus.SUCCESS).build());
            nodes.put(
                    "failure-end",
                    EndNode.builder().id("failure-end").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("action-fail")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "action-fail",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .nodes(nodes)
                            .startNode("action")
                            .build();

            // When
            ExecutionResult result = actionNodeExecutor.execute(workflow, new HashMap<>());

            // Then: action failure → FailureTransition → failure-end
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.FAILURE);
        }
    }

    @Nested
    class ExitStatusTest {

        @Test
        void shouldPropagateCancelExitStatus() throws Exception {
            // Given: workflow that routes to a CANCEL end node
            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "start",
                    StandardNode.builder()
                            .id("start")
                            .agentId("test-agent")
                            .prompt("Process")
                            .transitionRules(List.of(new SuccessTransition("cancel-end")))
                            .build());
            nodes.put(
                    "cancel-end",
                    EndNode.builder().id("cancel-end").status(ExitStatus.CANCEL).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("cancel-test")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "cancel-test",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("start")
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.CANCEL);
        }
    }

    @Nested
    class TemplateResolutionTest {

        @Test
        void shouldResolvePreviousNodeOutputAsPlaceholder() throws Exception {
            // Given: step1 outputs "Hello", step2 prompt uses {step1}
            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "step1",
                    StandardNode.builder()
                            .id("step1")
                            .agentId("test-agent")
                            .prompt("Generate greeting")
                            .transitionRules(List.of(new SuccessTransition("step2")))
                            .build());
            nodes.put(
                    "step2",
                    StandardNode.builder()
                            .id("step2")
                            .agentId("test-agent")
                            .prompt("Write about {step1}")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("template-chain")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "template-chain",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("step1")
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Hello World"))
                    .thenReturn(AgentResponse.TextResponse.of("Article about Hello World"));

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: step1 output stored in context as "step1", used in step2's prompt
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getFinalState().getContext().get("step1"))
                    .isEqualTo("Hello World");
        }

        @Test
        void shouldResolveMultipleSourcesInOnePrompt() throws Exception {
            // Given: context has {topic}, step1 output available as {step1}
            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "step1",
                    StandardNode.builder()
                            .id("step1")
                            .agentId("test-agent")
                            .prompt("Research {topic}")
                            .transitionRules(List.of(new SuccessTransition("step2")))
                            .build());
            nodes.put(
                    "step2",
                    StandardNode.builder()
                            .id("step2")
                            .agentId("test-agent")
                            .prompt("Write about {topic} using research: {step1}")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("multi-source")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "multi-source",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("step1")
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(eq("Research AI"), any()))
                    .thenReturn(AgentResponse.TextResponse.of("AI research findings"));
            when(mockAgent.execute(
                            eq("Write about AI using research: AI research findings"), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Final article"));

            Map<String, Object> context = new HashMap<>();
            context.put("topic", "AI");

            // When
            ExecutionResult result = executor.execute(workflow, context);

            // Then
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getFinalState().getContext().get("step1"))
                    .isEqualTo("AI research findings");
        }
    }

    @Nested
    class ParallelConsensusTest {

        @Test
        void shouldExecuteBranchesAndReachMajorityConsensus() throws Exception {
            // Given: 3 branches, 2 approve with keywords → MAJORITY_VOTE → consensus reached
            Agent agent1 = org.mockito.Mockito.mock(Agent.class);
            Agent agent2 = org.mockito.Mockito.mock(Agent.class);
            Agent agent3 = org.mockito.Mockito.mock(Agent.class);

            when(agentRegistry.getAgent("reviewer1")).thenReturn(Optional.of(agent1));
            when(agentRegistry.getAgent("reviewer2")).thenReturn(Optional.of(agent2));
            when(agentRegistry.getAgent("reviewer3")).thenReturn(Optional.of(agent3));

            when(agent1.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I approve this work. Score: 90"));
            when(agent2.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I approve. Score: 85"));
            when(agent3.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I reject this. Score: 30"));

            Map<String, AgentConfig> agents =
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

            Map<String, Node> nodes = new HashMap<>();
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
            nodes.put(
                    "success-end",
                    EndNode.builder().id("success-end").status(ExitStatus.SUCCESS).build());
            nodes.put(
                    "failure-end",
                    EndNode.builder().id("failure-end").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("consensus-majority")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "consensus-majority",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("parallel")
                            .build();

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: 2/3 approve → majority consensus → SUCCESS
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldFailConsensusWhenMajorityRejects() throws Exception {
            // Given: 3 branches, 2 reject → MAJORITY_VOTE → no consensus
            Agent agent1 = org.mockito.Mockito.mock(Agent.class);
            Agent agent2 = org.mockito.Mockito.mock(Agent.class);
            Agent agent3 = org.mockito.Mockito.mock(Agent.class);

            when(agentRegistry.getAgent("reviewer1")).thenReturn(Optional.of(agent1));
            when(agentRegistry.getAgent("reviewer2")).thenReturn(Optional.of(agent2));
            when(agentRegistry.getAgent("reviewer3")).thenReturn(Optional.of(agent3));

            when(agent1.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I approve. Score: 90"));
            when(agent2.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I reject this. Score: 20"));
            when(agent3.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I reject this. Score: 15"));

            Map<String, AgentConfig> agents =
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

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "parallel",
                    ParallelNode.builder("parallel")
                            .branch("b1", "reviewer1", "Review this")
                            .branch("b2", "reviewer2", "Review this")
                            .branch("b3", "reviewer3", "Review this")
                            .consensus(null, ConsensusStrategy.MAJORITY_VOTE)
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("success-end"),
                                            new FailureTransition(0, "failure-end")))
                            .build());
            nodes.put(
                    "success-end",
                    EndNode.builder().id("success-end").status(ExitStatus.SUCCESS).build());
            nodes.put(
                    "failure-end",
                    EndNode.builder().id("failure-end").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("consensus-reject")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "consensus-reject",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("parallel")
                            .build();

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: 2/3 reject → no majority consensus → FAILURE
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.FAILURE);
        }

        @Test
        void shouldCollectBranchOutputsWithoutConsensus() throws Exception {
            // Given: 2 branches, no consensus config → outputs collected as map
            Agent agent1 = org.mockito.Mockito.mock(Agent.class);
            Agent agent2 = org.mockito.Mockito.mock(Agent.class);

            when(agentRegistry.getAgent("writer1")).thenReturn(Optional.of(agent1));
            when(agentRegistry.getAgent("writer2")).thenReturn(Optional.of(agent2));

            when(agent1.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Draft from writer 1"));
            when(agent2.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Draft from writer 2"));

            Map<String, AgentConfig> agents =
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

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "parallel",
                    ParallelNode.builder("parallel")
                            .branch("b1", "writer1", "Write draft")
                            .branch("b2", "writer2", "Write draft")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("no-consensus")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "no-consensus",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("parallel")
                            .build();

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: no consensus evaluation, outputs collected → SUCCESS
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldEvaluateBranchRubricInConsensus() throws Exception {
            // Given: 3 branches, 2 with rubricId; rubric passes for 2, fails for 1
            Agent agent1 = org.mockito.Mockito.mock(Agent.class);
            Agent agent2 = org.mockito.Mockito.mock(Agent.class);
            Agent agent3 = org.mockito.Mockito.mock(Agent.class);

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

            Map<String, AgentConfig> agents =
                    Map.of(
                            "r1",
                                    AgentConfig.builder()
                                            .id("r1")
                                            .role("Reviewer")
                                            .model("test")
                                            .build(),
                            "r2",
                                    AgentConfig.builder()
                                            .id("r2")
                                            .role("Reviewer")
                                            .model("test")
                                            .build(),
                            "r3",
                                    AgentConfig.builder()
                                            .id("r3")
                                            .role("Reviewer")
                                            .model("test")
                                            .build());

            Map<String, Node> nodes = new HashMap<>();
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
            nodes.put(
                    "success-end",
                    EndNode.builder().id("success-end").status(ExitStatus.SUCCESS).build());
            nodes.put(
                    "failure-end",
                    EndNode.builder().id("failure-end").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("rubric-consensus")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "rubric-consensus",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("parallel")
                            .rubrics(Map.of("quality", "test-path"))
                            .build();

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: 2/3 rubric-passed → APPROVE → majority consensus → SUCCESS
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldSkipRubricWhenBranchHasNoRubricId() throws Exception {
            // Given: branches without rubricId → fall through to keyword heuristics
            Agent agent1 = org.mockito.Mockito.mock(Agent.class);
            Agent agent2 = org.mockito.Mockito.mock(Agent.class);

            when(agentRegistry.getAgent("r1")).thenReturn(Optional.of(agent1));
            when(agentRegistry.getAgent("r2")).thenReturn(Optional.of(agent2));

            when(agent1.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I approve this"));
            when(agent2.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I approve this"));

            Map<String, AgentConfig> agents =
                    Map.of(
                            "r1",
                                    AgentConfig.builder()
                                            .id("r1")
                                            .role("Reviewer")
                                            .model("test")
                                            .build(),
                            "r2",
                                    AgentConfig.builder()
                                            .id("r2")
                                            .role("Reviewer")
                                            .model("test")
                                            .build());

            Map<String, Node> nodes = new HashMap<>();
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
            nodes.put(
                    "success-end",
                    EndNode.builder().id("success-end").status(ExitStatus.SUCCESS).build());
            nodes.put(
                    "failure-end",
                    EndNode.builder().id("failure-end").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("no-rubric-consensus")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "no-rubric-consensus",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("parallel")
                            .build();

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: keyword "approve" → APPROVE for both → unanimous → SUCCESS
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        void shouldHandleRubricEvaluationFailureGracefully() throws Exception {
            // Given: rubricEngine throws → branch falls back to keyword heuristics
            Agent agent1 = org.mockito.Mockito.mock(Agent.class);
            Agent agent2 = org.mockito.Mockito.mock(Agent.class);

            when(agentRegistry.getAgent("r1")).thenReturn(Optional.of(agent1));
            when(agentRegistry.getAgent("r2")).thenReturn(Optional.of(agent2));

            when(agent1.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I approve this"));
            when(agent2.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("I approve this"));

            when(rubricEngine.exists("quality")).thenReturn(true);
            when(rubricEngine.evaluate(eq("quality"), any(), any()))
                    .thenThrow(new RubricNotFoundException("Rubric not found: quality"));

            Map<String, AgentConfig> agents =
                    Map.of(
                            "r1",
                                    AgentConfig.builder()
                                            .id("r1")
                                            .role("Reviewer")
                                            .model("test")
                                            .build(),
                            "r2",
                                    AgentConfig.builder()
                                            .id("r2")
                                            .role("Reviewer")
                                            .model("test")
                                            .build());

            Map<String, Node> nodes = new HashMap<>();
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
            nodes.put(
                    "success-end",
                    EndNode.builder().id("success-end").status(ExitStatus.SUCCESS).build());
            nodes.put(
                    "failure-end",
                    EndNode.builder().id("failure-end").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("rubric-failure-fallback")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "rubric-failure-fallback",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("parallel")
                            .rubrics(Map.of("quality", "test-path"))
                            .build();

            // When: rubric fails → falls back to keyword heuristics → "approve" → SUCCESS
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        }
    }

    @Nested
    class HumanReviewTest {

        private ReviewHandler mockReviewHandler;
        private WorkflowExecutor reviewExecutor;

        @BeforeEach
        void setUpReviewExecutor() {
            mockReviewHandler = org.mockito.Mockito.mock(ReviewHandler.class);
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
            // Given: node with DISABLED review → reviewHandler never called
            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "step",
                    StandardNode.builder()
                            .id("step")
                            .agentId("test-agent")
                            .prompt("Work")
                            .reviewConfig(new ReviewConfig(ReviewMode.DISABLED, false, false))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("review-disabled")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "review-disabled",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("step")
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

            // When
            ExecutionResult result = reviewExecutor.execute(workflow, new HashMap<>());

            // Then: reviewHandler never called, workflow continues
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            org.mockito.Mockito.verify(mockReviewHandler, org.mockito.Mockito.never())
                    .requestReview(any(), any(), any(), any(), any(), any());
        }

        @Test
        void shouldAutoApproveSuccessWhenReviewOptional() throws Exception {
            // Given: node with OPTIONAL review, agent succeeds → auto-approve
            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "step",
                    StandardNode.builder()
                            .id("step")
                            .agentId("test-agent")
                            .prompt("Work")
                            .reviewConfig(new ReviewConfig(ReviewMode.OPTIONAL, false, false))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("review-optional-success")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "review-optional-success",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("step")
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

            // When
            ExecutionResult result = reviewExecutor.execute(workflow, new HashMap<>());

            // Then: OPTIONAL + SUCCESS → auto-approve, reviewHandler not called
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            org.mockito.Mockito.verify(mockReviewHandler, org.mockito.Mockito.never())
                    .requestReview(any(), any(), any(), any(), any(), any());
        }

        @Test
        void shouldAlwaysRequestReviewWhenRequired() throws Exception {
            // Given: node with REQUIRED review, handler approves
            when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ReviewDecision.Approve(null));

            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "step",
                    StandardNode.builder()
                            .id("step")
                            .agentId("test-agent")
                            .prompt("Work")
                            .reviewConfig(new ReviewConfig(ReviewMode.REQUIRED, true, true))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("review-required")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "review-required",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("step")
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

            // When
            ExecutionResult result = reviewExecutor.execute(workflow, new HashMap<>());

            // Then: REQUIRED → reviewHandler called even on success
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            org.mockito.Mockito.verify(mockReviewHandler)
                    .requestReview(any(), any(), any(), any(), any(), any());
        }

        @Test
        void shouldRejectWorkflowOnReviewRejection() throws Exception {
            // Given: reviewer rejects
            when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ReviewDecision.Reject("Quality insufficient"));

            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "step",
                    StandardNode.builder()
                            .id("step")
                            .agentId("test-agent")
                            .prompt("Work")
                            .reviewConfig(new ReviewConfig(ReviewMode.REQUIRED, true, true))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("review-reject")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "review-reject",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("step")
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

            // When
            ExecutionResult result = reviewExecutor.execute(workflow, new HashMap<>());

            // Then: Reject → ExecutionResult.Rejected
            assertThat(result).isInstanceOf(ExecutionResult.Rejected.class);
            ExecutionResult.Rejected rejected = (ExecutionResult.Rejected) result;
            assertThat(rejected.getReason()).isEqualTo("Quality insufficient");
        }

        @Test
        void shouldBacktrackOnReviewBacktrack() throws Exception {
            // Given: step1 → step2 (REQUIRED review), reviewer backtracks to step1
            when(mockReviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenAnswer(
                            invocation -> {
                                HensuState state = invocation.getArgument(2);
                                return new ReviewDecision.Backtrack("step1", state, "Redo step 1");
                            })
                    .thenReturn(new ReviewDecision.Approve(null));

            Map<String, AgentConfig> agents =
                    Map.of(
                            "test-agent",
                            AgentConfig.builder()
                                    .id("test-agent")
                                    .role("Test")
                                    .model("test")
                                    .build());

            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "step1",
                    StandardNode.builder()
                            .id("step1")
                            .agentId("test-agent")
                            .prompt("Step 1")
                            .transitionRules(List.of(new SuccessTransition("step2")))
                            .build());
            nodes.put(
                    "step2",
                    StandardNode.builder()
                            .id("step2")
                            .agentId("test-agent")
                            .prompt("Step 2")
                            .reviewConfig(new ReviewConfig(ReviewMode.REQUIRED, true, true))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("review-backtrack")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "review-backtrack",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("step1")
                            .build();

            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
            when(mockAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Output"));

            // When
            ExecutionResult result = reviewExecutor.execute(workflow, new HashMap<>());

            // Then: backtrack step1 → re-execute step1 → step2 → approve → end
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
            // step1 executed twice, step2 executed twice → 4+ history entries
            assertThat(completed.getFinalState().getHistory().getSteps().size())
                    .isGreaterThanOrEqualTo(4);
        }
    }

    @Nested
    class ForkJoinTest {

        @Test
        void shouldForkTargetsAndJoinWithCollectAll() throws Exception {
            // Given: fork spawns taskA and taskB, join merges with COLLECT_ALL
            Agent agentA = org.mockito.Mockito.mock(Agent.class);
            Agent agentB = org.mockito.Mockito.mock(Agent.class);

            when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
            when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
            when(agentA.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Result A"));
            when(agentB.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Result B"));

            Map<String, AgentConfig> agents =
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

            Map<String, Node> nodes = new HashMap<>();
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
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());
            nodes.put(
                    "fail-end",
                    EndNode.builder().id("fail-end").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("fork-join-test")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "fork-join-test",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("fork1")
                            .build();

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: fork + join → merged results → SUCCESS
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
            assertThat(completed.getFinalState().getContext()).containsKey("fork_results");
        }

        @Test
        void shouldFailJoinWhenAnyErrorAndFailOnAnyErrorTrue() throws Exception {
            // Given: one fork target fails, failOnAnyError=true
            Agent agentA = org.mockito.Mockito.mock(Agent.class);
            Agent agentB = org.mockito.Mockito.mock(Agent.class);

            when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
            when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
            when(agentA.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Result A"));
            when(agentB.execute(any(), any())).thenReturn(AgentResponse.Error.of("Task B failed"));

            Map<String, AgentConfig> agents =
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

            Map<String, Node> nodes = new HashMap<>();
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
                            .failOnAnyError(true)
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("end"),
                                            new FailureTransition(0, "fail-end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());
            nodes.put(
                    "fail-end",
                    EndNode.builder().id("fail-end").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("fork-join-fail")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "fork-join-fail",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("fork1")
                            .build();

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: taskB failed, failOnAnyError=true → join returns FAILURE
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.FAILURE);
        }

        @Test
        void shouldContinueJoinWhenErrorAndFailOnAnyErrorFalse() throws Exception {
            // Given: one fork target fails, failOnAnyError=false
            Agent agentA = org.mockito.Mockito.mock(Agent.class);
            Agent agentB = org.mockito.Mockito.mock(Agent.class);

            when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
            when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
            when(agentA.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Result A"));
            when(agentB.execute(any(), any())).thenReturn(AgentResponse.Error.of("Task B failed"));

            Map<String, AgentConfig> agents =
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

            Map<String, Node> nodes = new HashMap<>();
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
                            .failOnAnyError(false)
                            .transitionRules(
                                    List.of(
                                            new SuccessTransition("end"),
                                            new FailureTransition(0, "fail-end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());
            nodes.put(
                    "fail-end",
                    EndNode.builder().id("fail-end").status(ExitStatus.FAILURE).build());

            Workflow workflow =
                    Workflow.builder()
                            .id("fork-join-continue")
                            .version("1.0.0")
                            .metadata(
                                    new WorkflowMetadata(
                                            "fork-join-continue",
                                            "Test",
                                            "tester",
                                            Instant.now(),
                                            List.of()))
                            .agents(agents)
                            .nodes(nodes)
                            .startNode("fork1")
                            .build();

            // When
            ExecutionResult result = executor.execute(workflow, new HashMap<>());

            // Then: failOnAnyError=false → merge successful results only → SUCCESS
            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
            assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        }
    }

    // Helper methods

    private Workflow createSimpleWorkflow() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("simple-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "simple-workflow", "Test", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithMissingNode() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(List.of(new SuccessTransition("missing-node")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("missing-node-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "missing-node-workflow",
                                "Test",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithTemplatePrompt() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Write about {topic} in {style} style")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("template-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "template-workflow", "Test", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createMultiStepWorkflow() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "step1",
                StandardNode.builder()
                        .id("step1")
                        .agentId("test-agent")
                        .prompt("Step 1")
                        .transitionRules(List.of(new SuccessTransition("step2")))
                        .build());
        nodes.put(
                "step2",
                StandardNode.builder()
                        .id("step2")
                        .agentId("test-agent")
                        .prompt("Step 2")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("multi-step-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "multi-step-workflow", "Test", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("step1")
                .build();
    }

    private Workflow createWorkflowWithFailureTransition() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("success-end"),
                                        new FailureTransition(0, "failure-end")))
                        .build());
        nodes.put(
                "success-end",
                EndNode.builder().id("success-end").status(ExitStatus.SUCCESS).build());
        nodes.put(
                "failure-end",
                EndNode.builder().id("failure-end").status(ExitStatus.FAILURE).build());

        return Workflow.builder()
                .id("failure-transition-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "failure-transition-workflow",
                                "Test",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithFailureEnd() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(List.of(new SuccessTransition("failure-end")))
                        .build());
        nodes.put(
                "failure-end",
                EndNode.builder().id("failure-end").status(ExitStatus.FAILURE).build());

        return Workflow.builder()
                .id("failure-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "failure-workflow", "Test", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithNoTransition() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(List.of()) // No transitions
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("no-transition-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "no-transition-workflow",
                                "Test",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithNullPrompt() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt(null)
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("null-prompt-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "null-prompt-workflow", "Test", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithOutputParams() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "step1",
                StandardNode.builder()
                        .id("step1")
                        .agentId("test-agent")
                        .prompt("Extract facts about Georgia")
                        .outputParams(List.of("lake_name", "peak_height", "capital"))
                        .transitionRules(List.of(new SuccessTransition("step2")))
                        .build());
        nodes.put(
                "step2",
                StandardNode.builder()
                        .id("step2")
                        .agentId("test-agent")
                        .prompt("Use the facts: {lake_name}, {peak_height}, {capital}")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("output-params-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "output-params-workflow",
                                "Test",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("step1")
                .build();
    }

    private Workflow createWorkflowWithParamPlaceholders() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "extract",
                StandardNode.builder()
                        .id("extract")
                        .agentId("test-agent")
                        .prompt("Extract country and language info")
                        .outputParams(List.of("country", "language"))
                        .transitionRules(List.of(new SuccessTransition("use")))
                        .build());
        nodes.put(
                "use",
                StandardNode.builder()
                        .id("use")
                        .agentId("test-agent")
                        .prompt("Write an article about {country} in {language}")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("param-placeholders-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "param-placeholders-workflow",
                                "Test",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("extract")
                .build();
    }
}
