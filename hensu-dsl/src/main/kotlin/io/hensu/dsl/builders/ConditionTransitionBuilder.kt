package io.hensu.dsl.builders

import io.hensu.core.workflow.transition.AlwaysTransition
import io.hensu.core.workflow.transition.Condition
import io.hensu.core.workflow.transition.ConditionTransition
import io.hensu.core.workflow.transition.TransitionRule

/**
 * DSL builder for transitions routed on an arbitrary declared output variable.
 *
 * Arms are evaluated in declaration order; the first matching arm determines the transition.
 * Composition across arms is OR-only – a conjunction such as `status == "complete" AND score >= 80`
 * is inexpressible by design; have the agent emit a combined variable instead. Overlapping arms are
 * a build error, and an explicit `otherwise` arm covers every remaining value.
 *
 * Example:
 * ```kotlin
 * onCondition("status") {
 *     whenValue equalTo "complete" goto "publish"
 *     whenValue notEqualTo "complete" revise "draft" retry 5 otherwise "escalate"
 * }
 * onCondition("confidence") {
 *     whenValue greaterThanOrEqual 0.9 goto "done"
 *     otherwise goto "review"
 * }
 * ```
 *
 * Unlike the older `goto`-handle builders, arms collect pending state and each rule is appended
 * exactly once when the block completes ([buildAll]) – `withFeedback` flips a flag on the pending
 * arm instead of replacing an already-added rule.
 *
 * @property variable name of the declared writes variable this block routes on
 * @see ConditionTransition for the compiled rule and coercion contract
 * @see ScoreTransitionBuilder for the score-specific counterpart
 */
@WorkflowDsl
class ConditionTransitionBuilder internal constructor(private val variable: String) {

    internal sealed interface Arm

    internal class GotoArm(val condition: Condition, val target: String) : Arm {
        var withFeedback = false
    }

    internal class ReviseArm(val condition: Condition) : Arm {
        val rules = mutableListOf<TransitionRule>()
    }

    internal class ElseGotoArm(val target: String) : Arm {
        var withFeedback = false
    }

    internal class ElseReviseArm : Arm {
        val rules = mutableListOf<TransitionRule>()
    }

    private val arms = mutableListOf<Arm>()

    /** Access to [whenValue] marker for arm syntax. */
    @Suppress("RemoveRedundantQualifierName")
    val whenValue: whenValue
        get() = io.hensu.dsl.builders.whenValue

    /**
     * Starts the explicit else-arm covering every value no earlier arm matched.
     *
     * Usage: `otherwise goto "review"` or `otherwise revise "draft" retry 3 otherwise "escalate"`
     */
    val otherwise: ConditionElseBuilder
        get() = ConditionElseBuilder(this)

    /**
     * Creates an equality arm against the value's canonical string form.
     *
     * Usage: `whenValue equalTo "complete" goto "publish"` – also accepts `equalTo true` or
     * `equalTo 5`.
     *
     * @param expected scalar to compare against (String, Boolean, or Number)
     * @return builder for specifying the transition target
     */
    infix fun whenValue.equalTo(expected: Any): ConditionArmBuilder =
        ConditionArmBuilder(this@ConditionTransitionBuilder, Condition.Equals(canonical(expected)))

    /**
     * Creates an inequality arm against the value's canonical string form.
     *
     * Usage: `whenValue notEqualTo "complete" revise "draft" retry 5 otherwise "escalate"`
     *
     * @param expected scalar to compare against (String, Boolean, or Number)
     * @return builder for specifying the transition target
     */
    infix fun whenValue.notEqualTo(expected: Any): ConditionArmBuilder =
        ConditionArmBuilder(
            this@ConditionTransitionBuilder,
            Condition.NotEquals(canonical(expected)),
        )

    /**
     * Creates a greater-than arm for numeric values.
     *
     * @param threshold value to compare against
     * @return builder for specifying the transition target
     */
    infix fun whenValue.greaterThan(threshold: Number): ConditionArmBuilder =
        compareArm(Condition.Op.GT, threshold)

    /**
     * Creates a greater-than-or-equal arm for numeric values.
     *
     * @param threshold value to compare against
     * @return builder for specifying the transition target
     */
    infix fun whenValue.greaterThanOrEqual(threshold: Number): ConditionArmBuilder =
        compareArm(Condition.Op.GTE, threshold)

    /**
     * Creates a less-than arm for numeric values.
     *
     * @param threshold value to compare against
     * @return builder for specifying the transition target
     */
    infix fun whenValue.lessThan(threshold: Number): ConditionArmBuilder =
        compareArm(Condition.Op.LT, threshold)

    /**
     * Creates a less-than-or-equal arm for numeric values.
     *
     * @param threshold value to compare against
     * @return builder for specifying the transition target
     */
    infix fun whenValue.lessThanOrEqual(threshold: Number): ConditionArmBuilder =
        compareArm(Condition.Op.LTE, threshold)

    private fun compareArm(op: Condition.Op, threshold: Number): ConditionArmBuilder =
        ConditionArmBuilder(this, Condition.Compare(op, threshold.toDouble()))

    private fun canonical(expected: Any): String {
        val canonical = Condition.canonicalize(expected)
        requireNotNull(canonical) {
            "onCondition(\"$variable\"): expected value must be a String, Boolean, or Number, " +
                "got ${expected::class.simpleName}"
        }
        return canonical
    }

    internal fun variableName(): String = variable

    internal fun addArm(arm: Arm) {
        check(arms.none { it is ElseGotoArm || it is ElseReviseArm }) {
            "onCondition(\"$variable\"): no arms may follow the otherwise arm"
        }
        arms.add(arm)
    }

    /**
     * Builds all transition rules from this condition block, in declaration order.
     *
     * Validates that no two arms overlap (some value matching both) – first-match-wins ordering
     * would silently mask the later arm.
     *
     * @return list of compiled transition rules in declaration order
     */
    fun buildAll(): List<TransitionRule> {
        validateNoOverlap()
        val rules = mutableListOf<TransitionRule>()
        for (arm in arms) {
            when (arm) {
                is GotoArm ->
                    rules.add(
                        ConditionTransition(variable, arm.condition, arm.target, arm.withFeedback)
                    )
                is ReviseArm -> {
                    check(arm.rules.isNotEmpty()) {
                        "onCondition(\"$variable\"): revise arm (${arm.condition.describe()}) " +
                            "is incomplete – expected: revise \"node\" retry N otherwise \"node\""
                    }
                    rules.addAll(arm.rules)
                }
                is ElseGotoArm -> rules.add(AlwaysTransition(arm.target, arm.withFeedback))
                is ElseReviseArm -> {
                    check(arm.rules.isNotEmpty()) {
                        "onCondition(\"$variable\"): otherwise revise arm is incomplete – " +
                            "expected: otherwise revise \"node\" retry N otherwise \"node\""
                    }
                    rules.addAll(arm.rules)
                }
            }
        }
        return rules
    }

    private fun validateNoOverlap() {
        val conditions =
            arms.mapNotNull {
                when (it) {
                    is GotoArm -> it.condition
                    is ReviseArm -> it.condition
                    else -> null
                }
            }
        for (i in conditions.indices) {
            for (j in i + 1 until conditions.size) {
                check(!ArmIntervals.overlap(conditions[i], conditions[j])) {
                    "onCondition(\"$variable\"): arms '${conditions[i].describe()}' and " +
                        "'${conditions[j].describe()}' overlap – some value matches both, and " +
                        "first-match-wins ordering would silently mask the later arm"
                }
            }
        }
    }
}

/**
 * Builder completing an individual condition arm with a transition target.
 *
 * Created by [ConditionTransitionBuilder] operator methods to enable fluent syntax: `whenValue
 * equalTo "complete" goto "publish"` or `whenValue notEqualTo "complete" revise "draft" retry 5
 * otherwise "escalate"`.
 *
 * @property builder the owning condition block
 * @property condition the predicate for this arm
 */
class ConditionArmBuilder
internal constructor(
    private val builder: ConditionTransitionBuilder,
    private val condition: Condition,
) {
    /**
     * Completes the arm with a direct transition target.
     *
     * Usage: `whenValue equalTo "complete" goto "publish"` or `... goto "publish" withFeedback`
     *
     * @param targetNode the node to transition to when this arm matches
     * @return handle for optional `withFeedback` chaining
     */
    infix fun goto(targetNode: String): ConditionFeedbackHandle {
        val arm = ConditionTransitionBuilder.GotoArm(condition, targetNode)
        builder.addArm(arm)
        return ConditionFeedbackHandle { arm.withFeedback = true }
    }

    /**
     * Creates a bounded-revise arm for this condition.
     *
     * Desugars to a [io.hensu.core.workflow.transition.BoundedTransition] wrapping a
     * [ConditionTransition] under the `"condition"` namespace.
     *
     * Usage: `whenValue notEqualTo "complete" revise "draft" retry 5 otherwise "escalate"`
     *
     * @param producerNode the node to re-execute on each retry
     * @return builder for specifying retry budget and escalation target
     */
    infix fun revise(producerNode: String): ReviseBuilder {
        val arm = ConditionTransitionBuilder.ReviseArm(condition)
        builder.addArm(arm)
        return ReviseBuilder(
            ConditionTransition(builder.variableName(), condition, producerNode),
            "condition",
            arm.rules,
        )
    }
}

/**
 * Builder completing the explicit else-arm of a condition block.
 *
 * @property builder the owning condition block
 */
class ConditionElseBuilder internal constructor(private val builder: ConditionTransitionBuilder) {
    /**
     * Routes every unmatched value to a direct target.
     *
     * Usage: `otherwise goto "review"` or `otherwise goto "review" withFeedback`
     *
     * @param targetNode the node to transition to
     * @return handle for optional `withFeedback` chaining
     */
    infix fun goto(targetNode: String): ConditionFeedbackHandle {
        val arm = ConditionTransitionBuilder.ElseGotoArm(targetNode)
        builder.addArm(arm)
        return ConditionFeedbackHandle { arm.withFeedback = true }
    }

    /**
     * Creates a bounded-revise arm for every unmatched value.
     *
     * Usage: `otherwise revise "draft" retry 3 otherwise "escalate"`
     *
     * @param producerNode the node to re-execute on each retry
     * @return builder for specifying retry budget and escalation target
     */
    infix fun revise(producerNode: String): ReviseBuilder {
        val arm = ConditionTransitionBuilder.ElseReviseArm()
        builder.addArm(arm)
        return ReviseBuilder(AlwaysTransition(producerNode), "condition", arm.rules)
    }
}

/**
 * Handle allowing optional `withFeedback` on a pending condition arm.
 *
 * Flips a flag on the pending arm – the rule itself is constructed once at block completion, so no
 * list surgery is involved.
 */
class ConditionFeedbackHandle internal constructor(private val markFeedback: () -> Unit) {
    /**
     * Marks the pending arm as feedback-preserving.
     *
     * Usage: `whenValue equalTo "partial" goto "refine" withFeedback`
     */
    val withFeedback: Unit
        get() = markFeedback()
}
