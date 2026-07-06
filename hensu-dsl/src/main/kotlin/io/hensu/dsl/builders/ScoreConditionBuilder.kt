package io.hensu.dsl.builders

import io.hensu.core.rubric.model.ComparisonOperator
import io.hensu.core.rubric.model.DoubleRange
import io.hensu.core.rubric.model.ScoreCondition

/**
 * Builder for completing an individual score condition with a transition target.
 *
 * Created by [ScoreTransitionBuilder] comparison methods to enable fluent syntax: `whenScore
 * greaterThan 80.0 goto "success"` or `whenScore lessThan 60.0 revise "producer" retry 3 otherwise
 * "escalate"`. The condition target arrives here, after the comparison – the completed arm is
 * recorded back on the owning block builder.
 *
 * @property operator comparison operator (GT, GTE, LT, LTE, RANGE)
 * @property value threshold value for comparison (unused for RANGE)
 * @property range inclusive range for RANGE operator, null otherwise
 * @property owner the score block this arm belongs to
 */
class ScoreConditionBuilder
internal constructor(
    private val operator: ComparisonOperator,
    private val value: Double,
    private val range: DoubleRange?,
    private val owner: ScoreTransitionBuilder,
) {
    /**
     * Completes the score condition by specifying the transition target.
     *
     * Usage: `whenScore greaterThan 80.0 goto "success"` or `whenScore lessThan 70.0 goto "write"
     * withFeedback`
     *
     * @param targetNode the node to transition to when this condition matches, not null
     * @return handle for optional `withFeedback` chaining
     */
    infix fun goto(targetNode: String): ScoreGotoHandle {
        val condition = ScoreCondition(operator, value, range, targetNode)
        owner.addGoto(condition)
        return ScoreGotoHandle(condition, owner)
    }

    /**
     * Creates a bounded-revise transition for this score condition.
     *
     * The inner trigger is a [io.hensu.core.workflow.transition.ScoreTransition] containing this
     * condition, so the bounded decorator fires only when the score matches.
     *
     * Usage: `whenScore lessThan 60.0 revise "producer" retry 3 otherwise "escalate"`
     *
     * @param producerNode the node to re-execute on each retry
     * @return builder for specifying retry budget and escalation target
     */
    infix fun revise(producerNode: String): ReviseBuilder =
        owner.addRevise(ScoreCondition(operator, value, range, producerNode))
}

/**
 * Handle returned by [ScoreConditionBuilder.goto] to allow optional `withFeedback` chaining.
 *
 * When `withFeedback` is accessed, the owning block moves the condition from the standard list to
 * the feedback list, causing [ScoreTransitionBuilder.buildAll] to emit a separate
 * feedback-preserving `ScoreTransition`.
 */
class ScoreGotoHandle
internal constructor(
    private val condition: ScoreCondition,
    private val owner: ScoreTransitionBuilder,
) {
    /**
     * Marks this transition as feedback-preserving.
     *
     * Usage: `whenScore lessThan 70.0 goto "write" withFeedback`
     */
    val withFeedback: Unit
        get() = owner.markFeedback(condition)
}
