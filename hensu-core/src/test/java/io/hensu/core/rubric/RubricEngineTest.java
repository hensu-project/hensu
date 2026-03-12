package io.hensu.core.rubric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.rubric.model.Criterion;
import io.hensu.core.rubric.model.Rubric;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RubricEngineTest {

    private RubricEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RubricEngine(new InMemoryRubricRepository(), (_, _, _) -> 0.0);
    }

    @Test
    void shouldThrowWhenRubricNotRegistered() {
        assertThatThrownBy(() -> engine.evaluate("missing", NodeResult.empty(), new HashMap<>()))
                .isInstanceOf(RubricNotFoundException.class);
    }

    @Test
    void shouldReturnZeroScoreWhenEvaluatorReturnsZero() throws RubricNotFoundException {
        engine = engineWithFixedScore(0.0);
        engine.registerRubric(rubric(criterion("c1", 1.0)));

        RubricEvaluation eval = engine.evaluate("r1", NodeResult.empty(), new HashMap<>());

        assertThat(eval.getScore()).isZero();
        assertThat(eval.isPassed()).isFalse();
    }

    @Test
    void shouldReturnPerfectScoreWhenEvaluatorReturnsHundred() throws RubricNotFoundException {
        engine = engineWithFixedScore(100.0);
        engine.registerRubric(rubric(criterion("c1", 1.0)));

        RubricEvaluation eval = engine.evaluate("r1", NodeResult.empty(), new HashMap<>());

        assertThat(eval.getScore()).isEqualTo(100.0);
        assertThat(eval.isPassed()).isTrue();
    }

    @Test
    void shouldApplyWeightsProportionally() throws RubricNotFoundException {
        // c1: weight=3, score=100.0 → contributes 300.0
        // c2: weight=1, score=0.0   → contributes 0.0
        // finalScore = 300.0 / 4.0 = 75.0
        RubricEngine mixed =
                new RubricEngine(
                        new InMemoryRubricRepository(),
                        (criterion, _, _) -> criterion.getId().equals("c1") ? 100.0 : 0.0);

        mixed.registerRubric(rubric(criterion("c1", 3.0), criterion("c2", 1.0)));

        RubricEvaluation eval = mixed.evaluate("r1", NodeResult.empty(), new HashMap<>());

        assertThat(eval.getScore()).isEqualTo(75.0);
        assertThat(eval.isPassed()).isTrue();
    }

    @Test
    void shouldPassAtExactThreshold() throws RubricNotFoundException {
        // finalScore == passThreshold must pass (>= not >)
        engine = engineWithFixedScore(70.0);
        engine.registerRubric(rubric(criterion("c1", 1.0)));

        RubricEvaluation eval = engine.evaluate("r1", NodeResult.empty(), new HashMap<>());

        assertThat(eval.getScore()).isEqualTo(70.0);
        assertThat(eval.isPassed()).isTrue();
    }

    @Test
    void shouldFailWhenScoreJustBelowThreshold() throws RubricNotFoundException {
        engine = engineWithFixedScore(69.9);
        engine.registerRubric(rubric(criterion("c1", 1.0)));

        RubricEvaluation eval = engine.evaluate("r1", NodeResult.empty(), new HashMap<>());

        assertThat(eval.getScore()).isLessThan(70.0);
        assertThat(eval.isPassed()).isFalse();
    }

    @Test
    void shouldReturnZeroWhenAllCriteriaHaveZeroWeight() throws RubricNotFoundException {
        engine = engineWithFixedScore(100.0);
        engine.registerRubric(rubric(criterion("c1", 0.0)));

        RubricEvaluation eval = engine.evaluate("r1", NodeResult.empty(), new HashMap<>());

        assertThat(eval.getScore()).isZero();
        assertThat(eval.isPassed()).isFalse();
    }

    @Test
    void shouldIncludePerCriterionDetailInEvaluation() throws RubricNotFoundException {
        engine = engineWithFixedScore(85.0);
        engine.registerRubric(rubric(criterion("c1", 1.0)));

        RubricEvaluation eval = engine.evaluate("r1", NodeResult.empty(), new HashMap<>());

        assertThat(eval.getCriterionEvaluations()).hasSize(1);
        assertThat(eval.getCriterionEvaluations().getFirst().getCriterionId()).isEqualTo("c1");
        assertThat(eval.getCriterionEvaluations().getFirst().getScore()).isEqualTo(85.0);
    }

    // — Helpers ——————————————————————————————————————————————————————————————

    private RubricEngine engineWithFixedScore(double score) {
        return new RubricEngine(new InMemoryRubricRepository(), (_, _, _) -> score);
    }

    private Rubric rubric(Criterion... criteria) {
        return Rubric.builder()
                .id("r1")
                .name("Test Rubric")
                .passThreshold(70.0)
                .criteria(List.of(criteria))
                .build();
    }

    private Criterion criterion(String id, double weight) {
        return Criterion.builder().id(id).name(id).weight(weight).minScore(0.0).build();
    }
}
