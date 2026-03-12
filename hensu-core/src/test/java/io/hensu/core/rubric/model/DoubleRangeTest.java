package io.hensu.core.rubric.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DoubleRangeTest {

    @Test
    void shouldRejectInvertedRange() {
        assertThatThrownBy(() -> new DoubleRange(100.0, 50.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Start must be less than or equal to end");
    }

    @Test
    void shouldExcludeValuesOutsideBounds() {
        DoubleRange range = new DoubleRange(10.0, 90.0);

        assertThat(range.contains(9.9)).isFalse();
        assertThat(range.contains(90.1)).isFalse();
    }

    @Test
    void shouldMatchOnlyExactValueForDegenerateRange() {
        DoubleRange range = new DoubleRange(50.0, 50.0);

        assertThat(range.contains(50.0)).isTrue();
        assertThat(range.contains(49.9)).isFalse();
        assertThat(range.contains(50.1)).isFalse();
    }

    @Test
    void shouldRespectDecimalPrecisionAtBoundaries() {
        DoubleRange range = new DoubleRange(0.001, 0.999);

        assertThat(range.contains(0.001)).isTrue();
        assertThat(range.contains(0.999)).isTrue();
        assertThat(range.contains(0.0009)).isFalse();
        assertThat(range.contains(1.0)).isFalse();
    }
}
