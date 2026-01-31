package io.hensu.core.rubric.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScoreConditionTest {

    @Nested
    class GreaterThanTest {

        @Test
        void shouldMatchWhenScoreIsGreaterThanValue() {
            // Given
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.GT, 80.0, null, "excellent");

            // Then
            assertThat(condition.matches(85.0)).isTrue();
            assertThat(condition.matches(80.1)).isTrue();
        }

        @Test
        void shouldNotMatchWhenScoreIsEqualOrLess() {
            // Given
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.GT, 80.0, null, "excellent");

            // Then
            assertThat(condition.matches(80.0)).isFalse();
            assertThat(condition.matches(79.9)).isFalse();
        }
    }

    @Nested
    class GreaterThanOrEqualTest {

        @Test
        void shouldMatchWhenScoreIsGreaterThanOrEqual() {
            // Given
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.GTE, 80.0, null, "pass");

            // Then
            assertThat(condition.matches(80.0)).isTrue();
            assertThat(condition.matches(85.0)).isTrue();
        }

        @Test
        void shouldNotMatchWhenScoreIsLess() {
            // Given
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.GTE, 80.0, null, "pass");

            // Then
            assertThat(condition.matches(79.9)).isFalse();
        }
    }

    @Nested
    class LessThanTest {

        @Test
        void shouldMatchWhenScoreIsLessThanValue() {
            // Given
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.LT, 60.0, null, "fail");

            // Then
            assertThat(condition.matches(59.9)).isTrue();
            assertThat(condition.matches(50.0)).isTrue();
        }

        @Test
        void shouldNotMatchWhenScoreIsEqualOrGreater() {
            // Given
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.LT, 60.0, null, "fail");

            // Then
            assertThat(condition.matches(60.0)).isFalse();
            assertThat(condition.matches(70.0)).isFalse();
        }
    }

    @Nested
    class LessThanOrEqualTest {

        @Test
        void shouldMatchWhenScoreIsLessThanOrEqual() {
            // Given
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.LTE, 60.0, null, "needs-work");

            // Then
            assertThat(condition.matches(60.0)).isTrue();
            assertThat(condition.matches(50.0)).isTrue();
        }

        @Test
        void shouldNotMatchWhenScoreIsGreater() {
            // Given
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.LTE, 60.0, null, "needs-work");

            // Then
            assertThat(condition.matches(60.1)).isFalse();
        }
    }

    @Nested
    class EqualTest {

        @Test
        void shouldMatchWhenScoreIsEqual() {
            // Given
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.EQ, 100.0, null, "perfect");

            // Then
            assertThat(condition.matches(100.0)).isTrue();
        }

        @Test
        void shouldNotMatchWhenScoreIsNotEqual() {
            // Given
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.EQ, 100.0, null, "perfect");

            // Then
            assertThat(condition.matches(99.9)).isFalse();
            assertThat(condition.matches(100.1)).isFalse();
        }
    }

    @Nested
    class RangeTest {

        @Test
        void shouldMatchWhenScoreIsWithinRange() {
            // Given
            DoubleRange range = new DoubleRange(70.0, 80.0);
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.RANGE, null, range, "average");

            // Then
            assertThat(condition.matches(70.0)).isTrue();
            assertThat(condition.matches(75.0)).isTrue();
            assertThat(condition.matches(80.0)).isTrue();
        }

        @Test
        void shouldNotMatchWhenScoreIsOutsideRange() {
            // Given
            DoubleRange range = new DoubleRange(70.0, 80.0);
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.RANGE, null, range, "average");

            // Then
            assertThat(condition.matches(69.9)).isFalse();
            assertThat(condition.matches(80.1)).isFalse();
        }

        @Test
        void shouldNotMatchWhenRangeIsNull() {
            // Given
            ScoreCondition condition =
                    new ScoreCondition(ComparisonOperator.RANGE, null, null, "average");

            // Then
            assertThat(condition.matches(75.0)).isFalse();
        }
    }

    @Test
    void shouldExposeAllFields() {
        // Given
        DoubleRange range = new DoubleRange(60.0, 80.0);
        ScoreCondition condition =
                new ScoreCondition(ComparisonOperator.GTE, 80.0, range, "target-node");

        // Then
        assertThat(condition.getOperator()).isEqualTo(ComparisonOperator.GTE);
        assertThat(condition.operator()).isEqualTo(ComparisonOperator.GTE);
        assertThat(condition.getValue()).isEqualTo(80.0);
        assertThat(condition.value()).isEqualTo(80.0);
        assertThat(condition.getTargetNode()).isEqualTo("target-node");
        assertThat(condition.targetNode()).isEqualTo("target-node");
        assertThat(condition.range()).isEqualTo(range);
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
        // Given
        ScoreCondition condition = new ScoreCondition(operator, value, null, "target");

        // Then
        assertThat(condition.matches(score)).isEqualTo(expected);
    }
}
