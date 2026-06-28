package io.hensu.dsl.builders

import io.hensu.core.workflow.transition.BoundedTransition
import io.hensu.core.workflow.transition.TransitionRule

/**
 * DSL builder for bounded-revise transitions.
 *
 * Completes the first segment of the `revise "producer" retry N otherwise "escalate"` chain by
 * capturing the inner trigger and namespace, then handing off to [ReviseRetryBuilder] for the
 * budget and escalation target.
 *
 * This builder is shared across all revise-capable triggers: `onNoConsensus`, `onRejection`, and
 * score-condition `revise`.
 *
 * @property trigger the inner transition rule fired on each retry attempt
 * @property namespace counter namespace for budget tracking (e.g. "consensus", "approval", "score")
 * @property rules mutable list to add the compiled [BoundedTransition]
 */
@WorkflowDsl
class ReviseBuilder
internal constructor(
    private val trigger: TransitionRule,
    private val namespace: String,
    private val rules: MutableList<TransitionRule>,
) {
    /**
     * Accepted as no-op – revise backtracks always preserve feedback by design.
     *
     * Allows `revise "producer" withFeedback retry 3 otherwise "escalate"` without breaking the
     * chain. Redundant but harmless.
     */
    val withFeedback: ReviseBuilder
        get() = this

    /**
     * Specifies the maximum retry attempts before escalation.
     *
     * Usage: `revise "producer" retry 3 otherwise "escalate"`
     *
     * @param count maximum retry attempts (must be positive)
     * @return builder for specifying the escalation target
     */
    infix fun retry(count: Int): ReviseRetryBuilder =
        ReviseRetryBuilder(trigger, namespace, count, rules)
}

/**
 * DSL builder for the final segment of a bounded-revise chain.
 *
 * Captures the retry budget and emits the [BoundedTransition] when the escalation target is
 * specified.
 *
 * @property trigger the inner transition rule fired on each retry attempt
 * @property namespace counter namespace for budget tracking
 * @property budget maximum retry attempts before escalation
 * @property rules mutable list to add the compiled [BoundedTransition]
 */
@WorkflowDsl
class ReviseRetryBuilder
internal constructor(
    private val trigger: TransitionRule,
    private val namespace: String,
    private val budget: Int,
    private val rules: MutableList<TransitionRule>,
) {
    /**
     * Specifies the escalation target after retries are exhausted.
     *
     * Adds a [BoundedTransition] wrapping the inner trigger with the configured namespace and
     * budget.
     *
     * Usage: `otherwise "escalate"` or `otherwise "escalate" withFeedback`
     *
     * @param targetNode the node to transition to after all retries fail
     * @return handle for optional `withFeedback` chaining on the escalation edge
     */
    infix fun otherwise(targetNode: String): OtherwiseHandle {
        rules.add(BoundedTransition(trigger, namespace, budget, targetNode))
        return OtherwiseHandle(rules, trigger, namespace, budget, targetNode)
    }
}

/**
 * Handle returned by [ReviseRetryBuilder.otherwise] to allow optional `withFeedback` on the
 * escalation edge.
 *
 * When `withFeedback` is accessed, the last-added [BoundedTransition] is replaced with one that has
 * `escalationWithFeedback=true`.
 */
class OtherwiseHandle
internal constructor(
    private val rules: MutableList<TransitionRule>,
    private val trigger: TransitionRule,
    private val namespace: String,
    private val budget: Int,
    private val targetNode: String,
) {
    /**
     * Marks the escalation transition as feedback-preserving.
     *
     * Usage: `otherwise "escalate" withFeedback`
     */
    val withFeedback: Unit
        get() {
            rules.removeLastOrNull()
            rules.add(BoundedTransition(trigger, namespace, budget, targetNode, true))
        }
}
