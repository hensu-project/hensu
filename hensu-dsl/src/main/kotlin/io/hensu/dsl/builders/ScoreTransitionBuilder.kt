package io.hensu.dsl.builders

import io.hensu.core.rubric.model.ComparisonOperator
import io.hensu.core.rubric.model.ScoreCondition
import io.hensu.core.workflow.transition.ScoreTransition
import io.hensu.core.workflow.transition.TransitionRule
import io.hensu.dsl.extensions.asJavaRange

/**
 * DSL builder for score-based conditional transitions.
 *
 * Enables routing based on rubric evaluation scores. Conditions are evaluated in order; the first
 * matching condition determines the transition target. Supports both direct `goto` transitions and
 * bounded `revise` transitions with retry budgets.
 *
 * Example:
 * ```kotlin
 * onScore {
 *     whenScore greaterThan 80.0 goto "approved"
 *     whenScore lessThan 60.0 revise "producer" retry 3 otherwise "escalate"
 * }
 * ```
 *
 * @see ScoreConditionBuilder for completing individual conditions
 * @see ScoreTransition for the compiled transition rule
 */
@WorkflowDsl
class ScoreTransitionBuilder {
    private val conditions = mutableListOf<ScoreCondition>()
    private val feedbackConditions = mutableListOf<ScoreCondition>()
    private val boundedRules = mutableListOf<TransitionRule>()

    /** Access to [whenScore] marker for condition syntax. */
    @Suppress("RemoveRedundantQualifierName")
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
        ScoreConditionBuilder(
            ComparisonOperator.GT,
            threshold,
            null,
            conditions,
            feedbackConditions,
            boundedRules,
        )

    /**
     * Creates a greater-than-or-equal condition.
     *
     * Usage: `whenScore greaterThanOrEqual 80.0 goto "success"`
     *
     * @param threshold score value to compare against
     * @return builder for specifying transition target
     */
    infix fun whenScore.greaterThanOrEqual(threshold: Double): ScoreConditionBuilder =
        ScoreConditionBuilder(
            ComparisonOperator.GTE,
            threshold,
            null,
            conditions,
            feedbackConditions,
            boundedRules,
        )

    /**
     * Creates a less-than condition.
     *
     * Usage: `whenScore lessThan 60.0 goto "failure"`
     *
     * @param threshold score value to compare against
     * @return builder for specifying transition target
     */
    infix fun whenScore.lessThan(threshold: Double): ScoreConditionBuilder =
        ScoreConditionBuilder(
            ComparisonOperator.LT,
            threshold,
            null,
            conditions,
            feedbackConditions,
            boundedRules,
        )

    /**
     * Creates a less-than-or-equal condition.
     *
     * Usage: `whenScore lessThanOrEqual 60.0 goto "failure"`
     *
     * @param threshold score value to compare against
     * @return builder for specifying transition target
     */
    infix fun whenScore.lessThanOrEqual(threshold: Double): ScoreConditionBuilder =
        ScoreConditionBuilder(
            ComparisonOperator.LTE,
            threshold,
            null,
            conditions,
            feedbackConditions,
            boundedRules,
        )

    /**
     * Creates a range condition (inclusive).
     *
     * Usage: `whenScore `in` 60.0..79.0 goto "revise"`
     *
     * @param range inclusive score range to check
     * @return builder for specifying transition target
     */
    infix fun whenScore.`in`(range: ClosedFloatingPointRange<Double>): ScoreConditionBuilder =
        ScoreConditionBuilder(
            ComparisonOperator.RANGE,
            0.0,
            range.asJavaRange,
            conditions,
            feedbackConditions,
            boundedRules,
        )

    /**
     * Builds all transition rules from this score block.
     *
     * Returns the [ScoreTransition] containing goto conditions (if any) followed by any bounded
     * revise rules. A revise-only score block (no goto arms) emits only the bounded rules – score
     * extraction still engages because `requiredEngineVars()` flows through the decorator.
     *
     * @return list of compiled transition rules, never empty
     */
    fun buildAll(): List<TransitionRule> {
        val result = mutableListOf<TransitionRule>()
        if (conditions.isNotEmpty()) {
            result.add(ScoreTransition(conditions.toList()))
        }
        if (feedbackConditions.isNotEmpty()) {
            result.add(ScoreTransition(feedbackConditions.toList(), true))
        }
        result.addAll(boundedRules)
        return result
    }
}
