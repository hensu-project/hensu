package io.hensu.core.rubric.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScoreConditionTest {

    @Test
    void shouldNotMatchWhenRangeIsNull() {
        ScoreCondition condition =
                new ScoreCondition(ComparisonOperator.RANGE, null, null, "average");

        assertThat(condition.matches(75.0)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "GT, 80.0, 85.0, true",
        "GT, 80.0, 80.0, false",
        "GTE, 80.0, 80.0, true",
        "GTE, 80.0, 79.0, false",
        "LT, 60.0, 59.0, true",
        "LT, 60.0, 60.0, false",
        "LTE, 60.0, 60.0, true",
        "LTE, 60.0, 61.0, false",
        "EQ, 100.0, 100.0, true",
        "EQ, 100.0, 99.0, false"
    })
    void shouldEvaluateOperatorsCorrectly(
            ComparisonOperator operator, double value, double score, boolean expected) {
        ScoreCondition condition = new ScoreCondition(operator, value, null, "target");

        assertThat(condition.matches(score)).isEqualTo(expected);
    }
}
