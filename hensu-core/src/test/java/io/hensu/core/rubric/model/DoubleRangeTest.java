package io.hensu.core.rubric.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DoubleRangeTest {

    @Test
    void shouldCreateValidRange() {
        // When
        DoubleRange range = new DoubleRange(0.0, 100.0);

        // Then
        assertThat(range.start()).isEqualTo(0.0);
        assertThat(range.end()).isEqualTo(100.0);
    }

    @Test
    void shouldAllowEqualStartAndEnd() {
        // When
        DoubleRange range = new DoubleRange(50.0, 50.0);

        // Then
        assertThat(range.start()).isEqualTo(50.0);
        assertThat(range.end()).isEqualTo(50.0);
    }

    @Test
    void shouldThrowExceptionWhenStartIsGreaterThanEnd() {
        // When/Then
        assertThatThrownBy(() -> new DoubleRange(100.0, 50.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Start must be less than or equal to end");
    }

    @Test
    void shouldContainValueWithinRange() {
        // Given
        DoubleRange range = new DoubleRange(0.0, 100.0);

        // Then
        assertThat(range.contains(0.0)).isTrue();
        assertThat(range.contains(50.0)).isTrue();
        assertThat(range.contains(100.0)).isTrue();
    }

    @Test
    void shouldNotContainValueOutsideRange() {
        // Given
        DoubleRange range = new DoubleRange(10.0, 90.0);

        // Then
        assertThat(range.contains(9.9)).isFalse();
        assertThat(range.contains(90.1)).isFalse();
        assertThat(range.contains(-1.0)).isFalse();
    }

    @Test
    void shouldContainExactValueWhenStartEqualsEnd() {
        // Given
        DoubleRange range = new DoubleRange(50.0, 50.0);

        // Then
        assertThat(range.contains(50.0)).isTrue();
        assertThat(range.contains(49.9)).isFalse();
        assertThat(range.contains(50.1)).isFalse();
    }

    @Test
    void shouldWorkWithNegativeValues() {
        // Given
        DoubleRange range = new DoubleRange(-100.0, -50.0);

        // Then
        assertThat(range.contains(-100.0)).isTrue();
        assertThat(range.contains(-75.0)).isTrue();
        assertThat(range.contains(-50.0)).isTrue();
        assertThat(range.contains(-49.9)).isFalse();
    }

    @Test
    void shouldWorkWithDecimalPrecision() {
        // Given
        DoubleRange range = new DoubleRange(0.001, 0.999);

        // Then
        assertThat(range.contains(0.001)).isTrue();
        assertThat(range.contains(0.5)).isTrue();
        assertThat(range.contains(0.999)).isTrue();
        assertThat(range.contains(0.0009)).isFalse();
        assertThat(range.contains(1.0)).isFalse();
    }
}
