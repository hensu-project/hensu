package io.hensu.dsl.builders

import io.hensu.core.workflow.transition.Condition
import io.hensu.core.workflow.transition.Condition.Compare
import io.hensu.core.workflow.transition.Condition.Equals
import io.hensu.core.workflow.transition.Condition.NotEquals
import io.hensu.core.workflow.transition.Condition.Op
import java.util.stream.Stream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ArmIntervalsTest {

    companion object {
        /**
         * Cross-kind overlap matrix. A false positive rejects a valid workflow at build time; a
         * false negative admits arms where first-match-wins silently masks the later one — both are
         * prod routing bugs, and each pair below exercises a distinct branch of
         * [ArmIntervals.overlap].
         */
        @JvmStatic
        fun predicatePairs(): Stream<Arguments> =
            Stream.of(
                // Equals vs Equals: only the identical canonical value collides
                Arguments.of(Equals("complete"), Equals("complete"), true),
                Arguments.of(Equals("complete"), Equals("blocked"), false),
                // NotEquals excludes exactly one value: complementary Equals is the
                // only non-overlapping partner
                Arguments.of(NotEquals("x"), Equals("x"), false),
                Arguments.of(Equals("x"), NotEquals("x"), false),
                Arguments.of(NotEquals("x"), Equals("y"), true),
                Arguments.of(NotEquals("x"), NotEquals("y"), true),
                Arguments.of(NotEquals("x"), Compare(Op.GTE, 80.0), true),
                // Equals vs Compare: overlaps only when the canonical form is a number
                // inside the interval — both argument orders hit different branches
                Arguments.of(Equals("85"), Compare(Op.GTE, 80.0), true),
                Arguments.of(Compare(Op.GTE, 80.0), Equals("85"), true),
                Arguments.of(Equals("70"), Compare(Op.GTE, 80.0), false),
                Arguments.of(Equals("excellent"), Compare(Op.GTE, 80.0), false),
                // Compare vs Compare: shared boundary point overlaps only when both
                // bounds are inclusive
                Arguments.of(Compare(Op.GTE, 80.0), Compare(Op.LT, 80.0), false),
                Arguments.of(Compare(Op.GTE, 80.0), Compare(Op.LTE, 80.0), true),
            )
    }

    @ParameterizedTest(name = "{0} vs {1} → overlap={2}")
    @MethodSource("predicatePairs")
    fun `should detect overlap across predicate kinds`(
        a: Condition,
        b: Condition,
        expected: Boolean,
    ) {
        assertThat(ArmIntervals.overlap(a, b)).isEqualTo(expected)
    }
}
