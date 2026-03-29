package io.hensu.core.rubric.evaluator;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.rubric.model.Criterion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScoreExtractingEvaluatorTest {

    private ScoreExtractingEvaluator evaluator;
    private Criterion criterion;

    @BeforeEach
    void setUp() {
        evaluator = new ScoreExtractingEvaluator();
        criterion = Criterion.builder().id("quality").name("Quality").minScore(70.0).build();
    }

    @Test
    void shouldReturnZeroForFailedNodeResult() {
        Map<String, Object> context = new HashMap<>();
        context.put(EngineVariables.SCORE, 95.0);

        double score = evaluator.evaluate(criterion, NodeResult.failure("agent error"), context);

        assertThat(score).isZero();
    }

    @Test
    void shouldReturnZeroWhenScoreKeyAbsent() {
        double score = evaluator.evaluate(criterion, NodeResult.empty(), new HashMap<>());

        assertThat(score).isZero();
    }

    @Test
    void shouldReturnNumericScoreDirectly() {
        Map<String, Object> context = new HashMap<>();
        context.put(EngineVariables.SCORE, 85.0);

        double score = evaluator.evaluate(criterion, NodeResult.empty(), context);

        assertThat(score).isEqualTo(85.0);
    }

    @Test
    void shouldParseScoreFromString() {
        Map<String, Object> context = new HashMap<>();
        context.put(EngineVariables.SCORE, "85.0");

        double score = evaluator.evaluate(criterion, NodeResult.empty(), context);

        assertThat(score).isEqualTo(85.0);
    }

    @Test
    void shouldReturnZeroForUnparsableScoreString() {
        Map<String, Object> context = new HashMap<>();
        context.put(EngineVariables.SCORE, "N/A");

        double score = evaluator.evaluate(criterion, NodeResult.empty(), context);

        assertThat(score).isZero();
    }

    @Test
    void shouldAccumulateRecommendationWhenScoreBelowThreshold() {
        Map<String, Object> context = new HashMap<>();
        context.put(EngineVariables.SCORE, 50.0);
        context.put(EngineVariables.RECOMMENDATION, "Add more detail to the answer.");

        evaluator.evaluate(criterion, NodeResult.empty(), context);

        List<String> recs =
                (List<String>) context.get(ScoreExtractingEvaluator.RECOMMENDATIONS_KEY);
        assertThat(recs)
                .isNotNull()
                .hasSize(1)
                .first()
                .asString()
                .contains("quality")
                .contains("Add more detail to the answer.");
    }

    @Test
    void shouldNotAccumulateRecommendationWhenScoreMeetsThreshold() {
        Map<String, Object> context = new HashMap<>();
        context.put(EngineVariables.SCORE, 70.0);
        context.put(EngineVariables.RECOMMENDATION, "This looks fine.");

        evaluator.evaluate(criterion, NodeResult.empty(), context);

        assertThat(context).doesNotContainKey(ScoreExtractingEvaluator.RECOMMENDATIONS_KEY);
    }

    @Test
    void shouldIgnoreBlankRecommendation() {
        Map<String, Object> context = new HashMap<>();
        context.put(EngineVariables.SCORE, 50.0);
        context.put(EngineVariables.RECOMMENDATION, "   ");

        evaluator.evaluate(criterion, NodeResult.empty(), context);

        assertThat(context).doesNotContainKey(ScoreExtractingEvaluator.RECOMMENDATIONS_KEY);
    }

    @Test
    void shouldAppendToExistingRecommendationListAcrossMultipleCriteria() {
        Criterion second = Criterion.builder().id("clarity").name("Clarity").minScore(70.0).build();

        Map<String, Object> context = new HashMap<>();
        context.put(EngineVariables.SCORE, 50.0);
        context.put(EngineVariables.RECOMMENDATION, "First fix.");
        evaluator.evaluate(criterion, NodeResult.empty(), context);

        context.put(EngineVariables.RECOMMENDATION, "Second fix.");
        evaluator.evaluate(second, NodeResult.empty(), context);

        List<String> recs =
                (List<String>) context.get(ScoreExtractingEvaluator.RECOMMENDATIONS_KEY);
        assertThat(recs).hasSize(2);
    }

    @Test
    void shouldNotCrashWhenRecommendationsKeyHoldsWrongType() {
        Map<String, Object> context = new HashMap<>();
        context.put(EngineVariables.SCORE, 50.0);
        context.put(EngineVariables.RECOMMENDATION, "Some fix.");
        context.put(ScoreExtractingEvaluator.RECOMMENDATIONS_KEY, "not-a-list");

        double score = evaluator.evaluate(criterion, NodeResult.empty(), context);

        assertThat(score).isEqualTo(50.0);
    }
}
