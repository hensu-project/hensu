package io.hensu.dsl.builders

import io.hensu.core.rubric.model.ComparisonOperator
import io.hensu.core.rubric.model.ScoreCondition
import io.hensu.core.workflow.transition.AlwaysTransition
import io.hensu.core.workflow.transition.ScoreTransition
import io.hensu.core.workflow.transition.TransitionRule
import io.hensu.dsl.extensions.asJavaRange

/**
 * DSL builder for score-based conditional transitions.
 *
 * Enables routing based on rubric evaluation scores. Conditions are evaluated in order; the first
 * matching condition determines the transition target. Supports both direct `goto` transitions and
 * bounded `revise` transitions with retry budgets. Overlapping arms are a build error, and an
 * explicit `otherwise` arm covers every remaining score.
 *
 * Example:
 * ```kotlin
 * onScore {
 *     whenScore greaterThan 80.0 goto "approved"
 *     whenScore lessThan 60.0 revise "producer" retry 3 otherwise "escalate"
 *     otherwise goto "review"
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
    private val allConditions = mutableListOf<ScoreCondition>()
    private val elseRules = mutableListOf<TransitionRule>()

    /** Access to [whenScore] marker for condition syntax. */
    @Suppress("RemoveRedundantQualifierName")
    val whenScore: whenScore
        get() = io.hensu.dsl.builders.whenScore

    /**
     * Starts the explicit else-arm covering every score no earlier arm matched.
     *
     * Usage: `otherwise goto "review"` or `otherwise revise "draft" retry 3 otherwise "escalate"`
     */
    val otherwise: ScoreElseBuilder
        get() = ScoreElseBuilder(elseRules)

    /**
     * Creates a greater-than condition.
     *
     * Usage: `whenScore greaterThan 80.0 goto "success"`
     *
     * @param threshold score value to compare against
     * @return builder for specifying transition target
     */
    infix fun whenScore.greaterThan(threshold: Double): ScoreConditionBuilder =
        arm(ComparisonOperator.GT, threshold)

    /**
     * Creates a greater-than-or-equal condition.
     *
     * Usage: `whenScore greaterThanOrEqual 80.0 goto "success"`
     *
     * @param threshold score value to compare against
     * @return builder for specifying transition target
     */
    infix fun whenScore.greaterThanOrEqual(threshold: Double): ScoreConditionBuilder =
        arm(ComparisonOperator.GTE, threshold)

    /**
     * Creates a less-than condition.
     *
     * Usage: `whenScore lessThan 60.0 goto "failure"`
     *
     * @param threshold score value to compare against
     * @return builder for specifying transition target
     */
    infix fun whenScore.lessThan(threshold: Double): ScoreConditionBuilder =
        arm(ComparisonOperator.LT, threshold)

    /**
     * Creates a less-than-or-equal condition.
     *
     * Usage: `whenScore lessThanOrEqual 60.0 goto "failure"`
     *
     * @param threshold score value to compare against
     * @return builder for specifying transition target
     */
    infix fun whenScore.lessThanOrEqual(threshold: Double): ScoreConditionBuilder =
        arm(ComparisonOperator.LTE, threshold)

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
            this@ScoreTransitionBuilder,
        )

    private fun arm(operator: ComparisonOperator, threshold: Double): ScoreConditionBuilder =
        ScoreConditionBuilder(operator, threshold, null, this)

    /** Records a completed goto arm (called back by [ScoreConditionBuilder]). */
    internal fun addGoto(condition: ScoreCondition) {
        conditions.add(condition)
        allConditions.add(condition)
    }

    /** Moves a goto arm to the feedback-preserving list (called back by [ScoreGotoHandle]). */
    internal fun markFeedback(condition: ScoreCondition) {
        conditions.remove(condition)
        feedbackConditions.add(condition)
    }

    /**
     * Records a revise arm and opens its bounded chain (called back by [ScoreConditionBuilder]).
     */
    internal fun addRevise(condition: ScoreCondition): ReviseBuilder {
        allConditions.add(condition)
        return ReviseBuilder(ScoreTransition(listOf(condition)), "score", boundedRules)
    }

    /**
     * Builds all transition rules from this score block.
     *
     * Returns the [ScoreTransition] containing goto conditions (if any) followed by any bounded
     * revise rules and the else-arm. A revise-only score block (no goto arms) emits only the
     * bounded rules – score extraction still engages because `requiredEngineVars()` flows through
     * the decorator.
     *
     * Validates that no two arms overlap (some score matching both) – first-match-wins ordering
     * would silently mask the later arm.
     *
     * @return list of compiled transition rules, never empty
     */
    fun buildAll(): List<TransitionRule> {
        validateNoOverlap()
        val result = mutableListOf<TransitionRule>()
        if (conditions.isNotEmpty()) {
            result.add(ScoreTransition(conditions.toList()))
        }
        if (feedbackConditions.isNotEmpty()) {
            result.add(ScoreTransition(feedbackConditions.toList(), true))
        }
        result.addAll(boundedRules)
        result.addAll(elseRules)
        return result
    }

    private fun validateNoOverlap() {
        for (i in allConditions.indices) {
            for (j in i + 1 until allConditions.size) {
                check(!ArmIntervals.overlap(allConditions[i], allConditions[j])) {
                    "onScore: arms '${describe(allConditions[i])}' and " +
                        "'${describe(allConditions[j])}' overlap – some score matches both, " +
                        "and first-match-wins ordering would silently mask the later arm"
                }
            }
        }
    }

    private fun describe(condition: ScoreCondition): String =
        when (condition.operator()) {
            ComparisonOperator.GT -> "> ${condition.value()}"
            ComparisonOperator.GTE -> ">= ${condition.value()}"
            ComparisonOperator.LT -> "< ${condition.value()}"
            ComparisonOperator.LTE -> "<= ${condition.value()}"
            ComparisonOperator.EQ -> "== ${condition.value()}"
            ComparisonOperator.RANGE ->
                "in ${condition.range().start()}..${condition.range().end()}"
        }
}

/**
 * Builder completing the explicit else-arm of a score block.
 *
 * @property elseRules mutable list receiving the compiled else rule(s), emitted after all arms
 */
class ScoreElseBuilder internal constructor(private val elseRules: MutableList<TransitionRule>) {
    /**
     * Routes every unmatched score to a direct target.
     *
     * Usage: `otherwise goto "review"` or `otherwise goto "review" withFeedback`
     *
     * @param targetNode the node to transition to
     * @return handle for optional `withFeedback` chaining
     */
    infix fun goto(targetNode: String): ConditionFeedbackHandle {
        check(elseRules.isEmpty()) { "onScore: only one otherwise arm is allowed" }
        elseRules.add(AlwaysTransition(targetNode))
        return ConditionFeedbackHandle {
            elseRules[elseRules.size - 1] = AlwaysTransition(targetNode, true)
        }
    }

    /**
     * Creates a bounded-revise arm for every unmatched score.
     *
     * Usage: `otherwise revise "draft" retry 3 otherwise "escalate"`
     *
     * @param producerNode the node to re-execute on each retry
     * @return builder for specifying retry budget and escalation target
     */
    infix fun revise(producerNode: String): ReviseBuilder {
        check(elseRules.isEmpty()) { "onScore: only one otherwise arm is allowed" }
        return ReviseBuilder(AlwaysTransition(producerNode), "score", elseRules)
    }
}
