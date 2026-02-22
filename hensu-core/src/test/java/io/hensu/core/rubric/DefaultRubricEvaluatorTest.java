package io.hensu.core.rubric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.*;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.rubric.evaluator.DefaultRubricEvaluator;
import io.hensu.core.rubric.model.Criterion;
import io.hensu.core.rubric.model.EvaluationType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Tests for {@link DefaultRubricEvaluator} — score extraction and fallback logic.
@DisplayName("DefaultRubricEvaluator")
class DefaultRubricEvaluatorTest {

    private DefaultRubricEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new DefaultRubricEvaluator();
    }

    @Test
    @DisplayName("extracts self-reported score from JSON block in output")
    void shouldExtractSelfReportedScoreFromJsonOutput() {
        String output =
                """
                Here is my analysis of the topic.

                The key points are:
                1. First point
                2. Second point

                {"score": 75, "recommendation": "Add more examples"}
                """;

        double score = evaluator.evaluate(criterion("quality", 70.0), result(output), context());

        assertEquals(75.0, score);
    }

    @Test
    @DisplayName("stores recommendation in context when score is below threshold")
    void shouldExtractRecommendationWhenScoreBelowThreshold() {
        String output =
                """
                Brief response.

                {"score": 45, "recommendation": "Need more detail and examples"}
                """;

        Map<String, Object> ctx = context();
        evaluator.evaluate(criterion("quality", 70.0), result(output), ctx);

        List<String> recommendations =
                (List<String>) ctx.get(DefaultRubricEvaluator.RECOMMENDATIONS_KEY);
        assertNotNull(recommendations);
        assertTrue(recommendations.getFirst().contains("Need more detail and examples"));
    }

    @Test
    @DisplayName("does not store recommendation when score is above threshold")
    void shouldNotStoreRecommendationWhenScoreAboveThreshold() {
        String output =
                """
                Comprehensive response.

                {"score": 85, "recommendation": ""}
                """;

        Map<String, Object> ctx = context();
        double score = evaluator.evaluate(criterion("quality", 70.0), result(output), ctx);

        assertEquals(85.0, score);
        assertNull(ctx.get(DefaultRubricEvaluator.RECOMMENDATIONS_KEY));
    }

    @Test
    @DisplayName("prefers score already present in context over parsed JSON")
    void shouldUseScoreFromContextIfPresent() {
        Map<String, Object> ctx = context();
        ctx.put("score", 88);

        double score =
                evaluator.evaluate(
                        criterion("quality", 70.0),
                        new NodeResult(ResultStatus.SUCCESS, "Some output", Map.of()),
                        ctx);

        assertEquals(88.0, score);
    }

    @Test
    @DisplayName("falls back to rule-based evaluation when no JSON score present")
    void shouldFallbackToRuleBasedEvaluationWithoutSelfScore() {
        Criterion ruleBased =
                Criterion.builder()
                        .id("quality")
                        .name("Quality Check")
                        .minScore(70.0)
                        .evaluationType(EvaluationType.AUTOMATED)
                        .evaluationLogic("status=success")
                        .build();

        Map<String, Object> ctx = context();
        ctx.put("status", "success");

        double score =
                evaluator.evaluate(
                        ruleBased,
                        new NodeResult(
                                ResultStatus.SUCCESS, "Simple response without JSON", Map.of()),
                        ctx);

        assertEquals(100.0, score);
    }

    @Test
    @DisplayName("returns zero and adds recommendation for FAILURE result")
    void shouldReturnZeroForFailedExecution() {
        Map<String, Object> ctx = context();

        double score =
                evaluator.evaluate(
                        criterion("quality", 70.0),
                        new NodeResult(ResultStatus.FAILURE, "Error occurred", Map.of()),
                        ctx);

        assertEquals(0.0, score);
        List<String> recommendations =
                (List<String>) ctx.get(DefaultRubricEvaluator.RECOMMENDATIONS_KEY);
        assertNotNull(recommendations);
        assertTrue(recommendations.getFirst().contains("failed"));
    }

    // --- Edge cases ---

    @Test
    @DisplayName("uses FIRST JSON block when multiple are present in output")
    void shouldUseFirstJsonBlockWhenMultiplePresent() {
        String output =
                """
                Initial assessment.

                {"score": 50, "recommendation": "needs work"}

                After revision:

                {"score": 90, "recommendation": ""}
                """;

        double score = evaluator.evaluate(criterion("quality", 70.0), result(output), context());

        // The evaluator finds the FIRST valid JSON block — 50, not 90.
        // This test documents current extraction strategy. If changed to "last wins",
        // this test fails — requiring explicit acknowledgement of the behavior change.
        assertThat(score).isEqualTo(50.0);
    }

    @Test
    @DisplayName("does not throw on malformed JSON in agent output")
    void shouldHandleMalformedJsonGracefully() {
        // LLMs regularly emit trailing commas, truncated JSON, or unquoted values.
        String output =
                """
                Here is my analysis.

                {"score": "not-a-number", "broken": true,}
                """;

        assertThatCode(
                        () ->
                                evaluator.evaluate(
                                        criterion("quality", 70.0), result(output), context()))
                .doesNotThrowAnyException();
    }

    // --- Helpers ---

    private Criterion criterion(String id, double minScore) {
        return Criterion.builder()
                .id(id)
                .name("Test Criterion")
                .minScore(minScore)
                .evaluationType(EvaluationType.AUTOMATED)
                .build();
    }

    private NodeResult result(String output) {
        return new NodeResult(ResultStatus.SUCCESS, output, Map.of());
    }

    private Map<String, Object> context() {
        return new HashMap<>();
    }
}
