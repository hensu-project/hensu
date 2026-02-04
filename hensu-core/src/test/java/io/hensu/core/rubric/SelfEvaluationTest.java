package io.hensu.core.rubric;

import static org.junit.jupiter.api.Assertions.*;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.agent.stub.StubAgent;
import io.hensu.core.agent.stub.StubResponseRegistry;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.rubric.evaluator.DefaultRubricEvaluator;
import io.hensu.core.rubric.model.Criterion;
import io.hensu.core.rubric.model.EvaluationType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Tests for the self-evaluation system.
class SelfEvaluationTest {

    private DefaultRubricEvaluator evaluator;
    private StubResponseRegistry registry;

    @BeforeEach
    void setUp() {
        evaluator = new DefaultRubricEvaluator();
        registry = StubResponseRegistry.getInstance();
        registry.clearResponses();
    }

    @Test
    void shouldExtractSelfReportedScoreFromJsonOutput() {
        // Given: Agent output with self-reported score
        String output =
                """
            Here is my analysis of the topic.

            The key points are:
            1. First point
            2. Second point

            {"score": 75, "recommendation": "Add more examples"}
            """;

        NodeResult result = new NodeResult(ResultStatus.SUCCESS, output, Map.of());
        Criterion criterion = createCriterion("quality", 70.0);
        Map<String, Object> context = new HashMap<>();

        // When: Evaluating
        double score = evaluator.evaluate(criterion, result, context);

        // Then: Self-reported score is extracted
        assertEquals(75.0, score);
    }

    @Test
    void shouldExtractRecommendationWhenScoreBelowThreshold() {
        // Given: Low score with recommendation
        String output =
                """
            Brief response.

            {"score": 45, "recommendation": "Need more detail and examples"}
            """;

        NodeResult result = new NodeResult(ResultStatus.SUCCESS, output, Map.of());
        Criterion criterion = createCriterion("quality", 70.0);
        Map<String, Object> context = new HashMap<>();

        // When: Evaluating
        double score = evaluator.evaluate(criterion, result, context);

        // Then: Score is extracted and recommendation is stored
        assertEquals(45.0, score);

        List<String> recommendations =
                (List<String>) context.get(DefaultRubricEvaluator.RECOMMENDATIONS_KEY);
        assertNotNull(recommendations);
        assertTrue(recommendations.getFirst().contains("Need more detail and examples"));
    }

    @Test
    void shouldNotStoreRecommendationWhenScoreAboveThreshold() {
        // Given: High score (no recommendation needed)
        String output =
                """
            Comprehensive response with all details.

            {"score": 85, "recommendation": ""}
            """;

        NodeResult result = new NodeResult(ResultStatus.SUCCESS, output, Map.of());
        Criterion criterion = createCriterion("quality", 70.0);
        Map<String, Object> context = new HashMap<>();

        // When: Evaluating
        double score = evaluator.evaluate(criterion, result, context);

        // Then: Score is extracted but no recommendation stored
        assertEquals(85.0, score);
        assertNull(context.get(DefaultRubricEvaluator.RECOMMENDATIONS_KEY));
    }

    @Test
    void shouldUseScoreFromContextIfPresent() {
        // Given: Score already in context (from outputParams extraction)
        NodeResult result = new NodeResult(ResultStatus.SUCCESS, "Some output", Map.of());
        Criterion criterion = createCriterion("quality", 70.0);
        Map<String, Object> context = new HashMap<>();
        context.put("score", 88);

        // When: Evaluating
        double score = evaluator.evaluate(criterion, result, context);

        // Then: Context score is used
        assertEquals(88.0, score);
    }

    @Test
    void shouldFallbackToRuleBasedEvaluationWithoutSelfScore() {
        // Given: Output without self-reported score
        String output = "Simple response without JSON";

        NodeResult result = new NodeResult(ResultStatus.SUCCESS, output, Map.of());
        Criterion criterion =
                Criterion.builder()
                        .id("quality")
                        .name("Quality Check")
                        .minScore(70.0)
                        .evaluationType(EvaluationType.AUTOMATED)
                        .evaluationLogic("status=success")
                        .build();

        Map<String, Object> context = new HashMap<>();
        context.put("status", "success");

        // When: Evaluating
        double score = evaluator.evaluate(criterion, result, context);

        // Then: Falls back to rule-based (equality check passes = 100)
        assertEquals(100.0, score);
    }

    @Test
    void shouldReturnZeroForFailedExecution() {
        // Given: Failed execution
        NodeResult result = new NodeResult(ResultStatus.FAILURE, "Error occurred", Map.of());
        Criterion criterion = createCriterion("quality", 70.0);
        Map<String, Object> context = new HashMap<>();

        // When: Evaluating
        double score = evaluator.evaluate(criterion, result, context);

        // Then: Score is 0 and recommendation is added
        assertEquals(0.0, score);

        List<String> recommendations =
                (List<String>) context.get(DefaultRubricEvaluator.RECOMMENDATIONS_KEY);
        assertNotNull(recommendations);
        assertTrue(recommendations.getFirst().contains("failed"));
    }

    @Test
    void stubAgentShouldLoadResponseFromRegistry() {
        // Given: Registered stub response
        registry.registerResponse(
                "writer",
                """
            Test content about the topic.
            {"score": 90, "recommendation": ""}
            """);

        AgentConfig config =
                AgentConfig.builder().id("writer").model("test-model").role("Test writer").build();

        StubAgent agent = new StubAgent("writer", config);
        Map<String, Object> context = new HashMap<>();

        // When: Executing
        var response = agent.execute("Write about AI", context);

        // Then: Registry response is used
        assertInstanceOf(AgentResponse.TextResponse.class, response);
        var textResponse = (AgentResponse.TextResponse) response;
        assertTrue(textResponse.content().contains("score"));
        assertTrue(textResponse.content().contains("90"));
    }

    @Test
    void stubAgentShouldUseScenarioFromContext() {
        // Given: Different responses for different scenarios
        registry.registerResponse(
                "default",
                "writer",
                """
            High quality content.
            {"score": 85}
            """);

        registry.registerResponse(
                "low_score",
                "writer",
                """
            Brief content.
            {"score": 45, "recommendation": "Add more detail"}
            """);

        AgentConfig config =
                AgentConfig.builder().id("writer").model("test-model").role("Writer").build();

        StubAgent agent = new StubAgent("writer", config);

        // When: Executing with low_score scenario
        Map<String, Object> context = new HashMap<>();
        context.put("stub_scenario", "low_score");
        var response = agent.execute("Write about AI", context);

        // Then: Low score response is used
        assertInstanceOf(AgentResponse.TextResponse.class, response);
        var textResponse = (AgentResponse.TextResponse) response;
        assertTrue(textResponse.content().contains("45"));
        assertTrue(textResponse.content().contains("Add more detail"));
    }

    private Criterion createCriterion(String id, double minScore) {
        return Criterion.builder()
                .id(id)
                .name("Test Criterion")
                .minScore(minScore)
                .evaluationType(EvaluationType.AUTOMATED)
                .build();
    }
}
