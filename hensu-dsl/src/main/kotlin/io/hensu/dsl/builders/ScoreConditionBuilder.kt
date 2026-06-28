package io.hensu.dsl.builders

import io.hensu.core.rubric.model.ComparisonOperator
import io.hensu.core.rubric.model.DoubleRange
import io.hensu.core.rubric.model.ScoreCondition
import io.hensu.core.workflow.transition.ScoreTransition
import io.hensu.core.workflow.transition.TransitionRule

/**
 * Builder for completing an individual score condition with a transition target.
 *
 * Created by [ScoreTransitionBuilder] comparison methods to enable fluent syntax: `whenScore
 * greaterThan 80.0 goto "success"` or `whenScore lessThan 60.0 revise "producer" retry 3 otherwise
 * "escalate"`.
 *
 * @property operator comparison operator (GT, GTE, LT, LTE, RANGE)
 * @property value threshold value for comparison (unused for RANGE)
 * @property range inclusive range for RANGE operator, null otherwise
 * @property conditions mutable list to add goto conditions
 * @property feedbackConditions mutable list to add goto conditions with feedback preservation
 * @property boundedRules mutable list to add bounded revise rules
 */
class ScoreConditionBuilder(
    private val operator: ComparisonOperator,
    private val value: Double,
    private val range: DoubleRange?,
    private val conditions: MutableList<ScoreCondition>,
    private val feedbackConditions: MutableList<ScoreCondition>,
    private val boundedRules: MutableList<TransitionRule>,
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
        conditions.add(condition)
        return ScoreGotoHandle(condition, conditions, feedbackConditions)
    }

    /**
     * Creates a bounded-revise transition for this score condition.
     *
     * The inner trigger is a [ScoreTransition] containing this condition, so the bounded decorator
     * fires only when the score matches.
     *
     * Usage: `whenScore lessThan 60.0 revise "producer" retry 3 otherwise "escalate"`
     *
     * @param producerNode the node to re-execute on each retry
     * @return builder for specifying retry budget and escalation target
     */
    infix fun revise(producerNode: String): ReviseBuilder {
        val arm = ScoreTransition(listOf(ScoreCondition(operator, value, range, producerNode)))
        return ReviseBuilder(arm, "score", boundedRules)
    }
}

/**
 * Handle returned by [ScoreConditionBuilder.goto] to allow optional `withFeedback` chaining.
 *
 * When `withFeedback` is accessed, the condition is moved from the standard list to the feedback
 * list, causing [ScoreTransitionBuilder.buildAll] to emit a separate [ScoreTransition] with
 * `withFeedback=true`.
 */
class ScoreGotoHandle
internal constructor(
    private val condition: ScoreCondition,
    private val conditions: MutableList<ScoreCondition>,
    private val feedbackConditions: MutableList<ScoreCondition>,
) {
    /**
     * Marks this transition as feedback-preserving.
     *
     * Usage: `whenScore lessThan 70.0 goto "write" withFeedback`
     */
    val withFeedback: Unit
        get() {
            conditions.remove(condition)
            feedbackConditions.add(condition)
        }
}
