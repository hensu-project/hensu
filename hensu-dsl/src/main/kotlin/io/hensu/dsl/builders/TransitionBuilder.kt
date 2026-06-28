package io.hensu.dsl.builders

import io.hensu.core.workflow.transition.ApprovalTransition
import io.hensu.core.workflow.transition.FailureTransition
import io.hensu.core.workflow.transition.NoConsensusTransition
import io.hensu.core.workflow.transition.SuccessTransition
import io.hensu.core.workflow.transition.TransitionRule

/**
 * DSL builder for workflow transition rules.
 *
 * Collects and compiles transition rules that determine the next node based on execution results.
 * Supports success, failure (with retry), no-consensus, approval/rejection, and score-based
 * transitions. Failure retry and revise triggers desugar to
 * [io.hensu.core.workflow.transition.BoundedTransition] decorators with namespaced retry budgets.
 *
 * Example:
 * ```kotlin
 * onSuccess goto "next"
 * onFailure retry 3 otherwise "error"
 * onScore {
 *     whenScore greaterThanOrEqual 80.0 goto "success"
 *     whenScore lessThan 80.0 revise "producer" retry 3 otherwise "escalate"
 * }
 * ```
 *
 * @see TransitionRule for compiled rule types
 * @see ScoreTransitionBuilder for score-based transitions
 * @see ReviseBuilder for bounded-revise chain
 */
@WorkflowDsl
class TransitionBuilder {
    private val rules = mutableListOf<TransitionRule>()

    internal fun rulesRef(): MutableList<TransitionRule> = rules

    /**
     * Adds a success transition (internal).
     *
     * @param targetNode the node to transition to on success
     */
    internal fun addSuccessTransition(targetNode: String, withFeedback: Boolean = false) {
        rules.add(SuccessTransition(targetNode, withFeedback))
    }

    /**
     * Adds a failure transition (internal).
     *
     * @param targetNode the node to transition to on failure
     */
    internal fun addFailureTransition(targetNode: String) {
        rules.add(FailureTransition(targetNode))
    }

    /**
     * Adds a no-consensus transition (internal).
     *
     * Used for `onNoConsensus goto` in parallel nodes.
     *
     * @param targetNode the node to transition to when consensus fails
     */
    internal fun addNoConsensusTransition(targetNode: String, withFeedback: Boolean = false) {
        rules.add(NoConsensusTransition(targetNode, withFeedback))
    }

    /**
     * Creates a revise builder for bounded-retry transitions (internal).
     *
     * @param trigger the inner transition rule fired on each retry attempt
     * @param namespace counter namespace for budget tracking
     * @return builder for configuring retry budget and escalation target
     */
    internal fun createReviseBuilder(trigger: TransitionRule, namespace: String): ReviseBuilder =
        ReviseBuilder(trigger, namespace, rules)

    /**
     * Creates a retry builder (internal).
     *
     * @param count maximum retry attempts
     * @return builder for configuring fallback target
     */
    internal fun createRetryBuilder(count: Int): RetryBuilder = RetryBuilder(count, rules)

    /**
     * Defines a success transition.
     *
     * Usage: `onSuccess goto "next_node"`
     *
     * @param targetNode the node to transition to
     */
    infix fun onSuccess.goto(targetNode: String): GotoHandle {
        addSuccessTransition(targetNode)
        return GotoHandle(rules) { SuccessTransition(targetNode, true) }
    }

    /**
     * Defines a direct failure transition without retry.
     *
     * Usage: `onFailure goto "error-node"`
     *
     * @param targetNode the node to transition to on failure
     */
    infix fun onFailure.goto(targetNode: String) {
        addFailureTransition(targetNode)
    }

    /**
     * Defines a failure transition with retry.
     *
     * Usage: `onFailure retry 3 otherwise "fallback"`
     *
     * @param count maximum retry attempts before fallback
     * @return builder for specifying fallback target
     */
    infix fun onFailure.retry(count: Int): RetryBuilder = createRetryBuilder(count)

    /**
     * Adds an approval transition (internal).
     *
     * @param expected `true` to route on approval; `false` to route on rejection
     * @param targetNode the node to transition to
     */
    internal fun addApprovalTransition(
        expected: Boolean,
        targetNode: String,
        withFeedback: Boolean = false,
    ) {
        rules.add(ApprovalTransition(expected, targetNode, withFeedback))
    }

    /**
     * Defines score-based conditional transitions.
     *
     * Supports both direct `goto` and bounded `revise` arms. Goto arms evaluate before revise arms;
     * score conditions are mutually exclusive ranges in practice.
     *
     * Usage:
     * ```kotlin
     * onScore {
     *     whenScore greaterThanOrEqual 80.0 goto "success"
     *     whenScore lessThan 80.0 revise "producer" retry 3 otherwise "escalate"
     * }
     * ```
     *
     * @param block score condition configuration block
     */
    fun onScore(block: ScoreTransitionBuilder.() -> Unit) {
        val builder = ScoreTransitionBuilder()
        builder.apply(block)
        rules.addAll(builder.buildAll())
    }

    /**
     * Builds the list of compiled transition rules.
     *
     * @return immutable list of transition rules
     */
    fun build(): List<TransitionRule> = rules.toList()
}

/**
 * Handle returned by `goto` methods to allow optional `withFeedback` chaining.
 *
 * When `withFeedback` is accessed, the last-added rule is replaced with a feedback-preserving
 * variant via the provided factory.
 *
 * @property rules the mutable rule list containing the just-added rule
 * @property feedbackFactory produces the feedback-preserving replacement rule
 */
class GotoHandle
internal constructor(
    private val rules: MutableList<TransitionRule>,
    private val feedbackFactory: () -> TransitionRule,
) {
    /**
     * Marks this transition as feedback-preserving.
     *
     * Usage: `onSuccess goto "node" withFeedback`
     */
    val withFeedback: Unit
        get() {
            rules.removeLastOrNull()
            rules.add(feedbackFactory())
        }
}
