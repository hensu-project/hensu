package io.hensu.dsl.builders

import io.hensu.core.rubric.model.ComparisonOperator
import io.hensu.core.rubric.model.ScoreCondition
import io.hensu.core.workflow.transition.ScoreTransition
import io.hensu.dsl.extensions.asJavaRange

/**
 * DSL builder for score-based conditional transitions.
 *
 * Enables routing based on rubric evaluation scores. Conditions are evaluated in order; the first
 * matching condition determines the transition target.
 *
 * Example:
 * ```kotlin
 * onScore {
 *     whenScore greaterThan 80.0 goto "approved"
 *     whenScore `in` 60.0..80.0 goto "revise"
 *     whenScore lessThan 60.0 goto "rejected"
 * }
 * ```
 *
 * @see ScoreConditionBuilder for completing individual conditions
 * @see ScoreTransition for the compiled transition rule
 */
@WorkflowDsl
class ScoreTransitionBuilder {
    private val conditions = mutableListOf<ScoreCondition>()

    /** Access to [whenScore] marker for condition syntax. */
    val whenScore: whenScore
        get() = io.hensu.dsl.builders.whenScore

    /**
     * Creates a greater-than condition.
     *
     * Usage: `whenScore greaterThan 80.0 goto "success"`
     *
     * @param threshold score value to compare against
     * @return builder for specifying transition target
     */
    infix fun whenScore.greaterThan(threshold: Double): ScoreConditionBuilder =
        ScoreConditionBuilder(ComparisonOperator.GT, threshold, null, conditions)

    /**
     * Creates a greater-than-or-equal condition.
     *
     * Usage: `whenScore greaterThanOrEqual 80.0 goto "success"`
     *
     * @param threshold score value to compare against
     * @return builder for specifying transition target
     */
    infix fun whenScore.greaterThanOrEqual(threshold: Double): ScoreConditionBuilder =
        ScoreConditionBuilder(ComparisonOperator.GTE, threshold, null, conditions)

    /**
     * Creates a less-than condition.
     *
     * Usage: `whenScore lessThan 60.0 goto "failure"`
     *
     * @param threshold score value to compare against
     * @return builder for specifying transition target
     */
    infix fun whenScore.lessThan(threshold: Double): ScoreConditionBuilder =
        ScoreConditionBuilder(ComparisonOperator.LT, threshold, null, conditions)

    /**
     * Creates a less-than-or-equal condition.
     *
     * Usage: `whenScore lessThanOrEqual 60.0 goto "failure"`
     *
     * @param threshold score value to compare against
     * @return builder for specifying transition target
     */
    infix fun whenScore.lessThanOrEqual(threshold: Double): ScoreConditionBuilder =
        ScoreConditionBuilder(ComparisonOperator.LTE, threshold, null, conditions)

    /**
     * Creates a range condition (inclusive).
     *
     * Usage: `whenScore `in` 60.0..79.0 goto "revise"`
     *
     * @param range inclusive score range to check
     * @return builder for specifying transition target
     */
    infix fun whenScore.`in`(range: ClosedFloatingPointRange<Double>): ScoreConditionBuilder =
        ScoreConditionBuilder(ComparisonOperator.RANGE, 0.0, range.asJavaRange, conditions)

    /**
     * Builds the [ScoreTransition] containing all defined conditions.
     *
     * @return compiled score transition rule, never null
     */
    fun build(): ScoreTransition = ScoreTransition(conditions.toList())
}
