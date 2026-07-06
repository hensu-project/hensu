package io.hensu.dsl.builders

import io.hensu.core.rubric.model.ComparisonOperator
import io.hensu.core.rubric.model.ScoreCondition
import io.hensu.core.workflow.transition.Condition

/**
 * Static overlap detection for conditional transition arms.
 *
 * Two arms overlap when some value can match both. Overlapping arms in an `onScore { }` or
 * `onCondition { }` block are a build error: first-match-wins ordering silently masks the later
 * arm, which is almost always an authoring mistake.
 */
internal object ArmIntervals {

    /** Numeric interval with open/closed bounds; `null` bound means unbounded. */
    internal data class Interval(
        val lo: Double?,
        val loInclusive: Boolean,
        val hi: Double?,
        val hiInclusive: Boolean,
    ) {
        fun intersects(other: Interval): Boolean {
            val lo1 = lo ?: Double.NEGATIVE_INFINITY
            val hi1 = hi ?: Double.POSITIVE_INFINITY
            val lo2 = other.lo ?: Double.NEGATIVE_INFINITY
            val hi2 = other.hi ?: Double.POSITIVE_INFINITY
            if (lo1 > hi2 || lo2 > hi1) return false
            if (lo1 == hi2 && !(loInclusive && other.hiInclusive)) return false
            if (lo2 == hi1 && !(other.loInclusive && hiInclusive)) return false
            return true
        }

        companion object {
            fun point(value: Double) = Interval(value, true, value, true)
        }
    }

    internal fun intervalOf(condition: ScoreCondition): Interval =
        when (condition.operator()) {
            ComparisonOperator.GT -> Interval(condition.value(), false, null, false)
            ComparisonOperator.GTE -> Interval(condition.value(), true, null, false)
            ComparisonOperator.LT -> Interval(null, false, condition.value(), false)
            ComparisonOperator.LTE -> Interval(null, false, condition.value(), true)
            ComparisonOperator.EQ -> Interval.point(condition.value())
            ComparisonOperator.RANGE ->
                Interval(condition.range().start(), true, condition.range().end(), true)
        }

    private fun intervalOf(compare: Condition.Compare): Interval =
        when (compare.op()!!) {
            Condition.Op.GT -> Interval(compare.threshold(), false, null, false)
            Condition.Op.GTE -> Interval(compare.threshold(), true, null, false)
            Condition.Op.LT -> Interval(null, false, compare.threshold(), false)
            Condition.Op.LTE -> Interval(null, false, compare.threshold(), true)
        }

    /** Returns true when some value can match both score conditions. */
    internal fun overlap(a: ScoreCondition, b: ScoreCondition): Boolean =
        intervalOf(a).intersects(intervalOf(b))

    /**
     * Returns true when some value can match both condition predicates.
     *
     * `NotEquals` matches everything except one canonical value, so it overlaps any other predicate
     * unless that predicate is `Equals` on the same excluded value. `Equals` overlaps a numeric
     * `Compare` only when its canonical form is a number inside the interval.
     */
    internal fun overlap(a: Condition, b: Condition): Boolean =
        when (a) {
            is Condition.Equals ->
                when (b) {
                    is Condition.Equals -> a.expected() == b.expected()
                    is Condition.NotEquals -> a.expected() != b.expected()
                    is Condition.Compare -> equalsMatchesCompare(a, b)
                }
            is Condition.NotEquals ->
                when (b) {
                    is Condition.Equals -> a.expected() != b.expected()
                    // Both exclude a single value each – everything else matches both.
                    is Condition.NotEquals -> true
                    // The interval minus one excluded point is never empty.
                    is Condition.Compare -> true
                }
            is Condition.Compare ->
                when (b) {
                    is Condition.Equals -> equalsMatchesCompare(b, a)
                    is Condition.NotEquals -> true
                    is Condition.Compare -> intervalOf(a).intersects(intervalOf(b))
                }
        }

    private fun equalsMatchesCompare(eq: Condition.Equals, cmp: Condition.Compare): Boolean {
        val number = Condition.toNumber(eq.expected()) ?: return false
        return intervalOf(cmp).intersects(Interval.point(number))
    }
}
