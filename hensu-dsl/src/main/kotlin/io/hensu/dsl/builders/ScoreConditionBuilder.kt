package io.hensu.dsl.builders

import io.hensu.core.rubric.model.ComparisonOperator
import io.hensu.core.rubric.model.DoubleRange
import io.hensu.core.rubric.model.ScoreCondition

/**
 * Builder for completing an individual score condition with a transition target.
 *
 * Created by [ScoreTransitionBuilder] comparison methods to enable fluent syntax: `whenScore
 * greaterThan 80.0 goto "success"`
 *
 * @property operator comparison operator (GT, GTE, LT, LTE, RANGE)
 * @property value threshold value for comparison (unused for RANGE)
 * @property range inclusive range for RANGE operator, null otherwise
 * @property conditions mutable list to add the completed condition
 */
class ScoreConditionBuilder(
    private val operator: ComparisonOperator,
    private val value: Double,
    private val range: DoubleRange?,
    private val conditions: MutableList<ScoreCondition>,
) {
    /**
     * Completes the score condition by specifying the transition target.
     *
     * Usage: `whenScore greaterThan 80.0 goto "success"`
     *
     * @param targetNode the node to transition to when this condition matches, not null
     */
    infix fun goto(targetNode: String) {
        conditions.add(ScoreCondition(operator, value, range, targetNode))
    }
}
