package io.hensu.dsl.builders

import io.hensu.core.workflow.transition.FailureTransition
import io.hensu.core.workflow.transition.SuccessTransition
import io.hensu.core.workflow.transition.TransitionRule

/**
 * DSL builder for workflow transition rules.
 *
 * Collects and compiles transition rules that determine the next node based on execution results.
 * Supports success, failure (with retry), and score-based transitions.
 *
 * Example:
 * ```kotlin
 * onSuccess goto "next"
 * onFailure retry 3 otherwise "error"
 * onScore {
 *     whenScore greaterThan 80.0 goto "success"
 *     whenScore lessThan 60.0 goto "failure"
 * }
 * ```
 *
 * @see TransitionRule for compiled rule types
 * @see ScoreTransitionBuilder for score-based transitions
 */
@WorkflowDsl
class TransitionBuilder {
    private val rules = mutableListOf<TransitionRule>()

    /**
     * Adds a success transition (internal).
     *
     * @param targetNode the node to transition to on success
     */
    internal fun addSuccessTransition(targetNode: String) {
        rules.add(SuccessTransition(targetNode))
    }

    /**
     * Adds a failure transition (internal).
     *
     * Used for `onNoConsensus goto` in parallel nodes.
     *
     * @param targetNode the node to transition to on failure
     */
    internal fun addFailureTransition(targetNode: String) {
        rules.add(FailureTransition(0, targetNode))
    }

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
    infix fun onSuccess.goto(targetNode: String) {
        addSuccessTransition(targetNode)
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
     * Defines score-based conditional transitions.
     *
     * Usage:
     * ```kotlin
     * onScore {
     *     whenScore greaterThan 80.0 goto "success"
     *     whenScore lessThan 60.0 goto "failure"
     * }
     * ```
     *
     * @param block score condition configuration block
     */
    fun onScore(block: ScoreTransitionBuilder.() -> Unit) {
        val builder = ScoreTransitionBuilder()
        builder.apply(block)
        rules.add(builder.build())
    }

    /**
     * Builds the list of compiled transition rules.
     *
     * @return immutable list of transition rules
     */
    fun build(): List<TransitionRule> = rules.toList()
}
