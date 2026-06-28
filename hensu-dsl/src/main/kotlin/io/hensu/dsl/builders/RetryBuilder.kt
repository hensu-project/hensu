package io.hensu.dsl.builders

import io.hensu.core.workflow.transition.BoundedTransition
import io.hensu.core.workflow.transition.FailureTransition
import io.hensu.core.workflow.transition.TransitionRule

/**
 * Builder for failure transitions with retry semantics.
 *
 * Completes the `onFailure retry N otherwise "target"` DSL syntax by specifying the fallback node
 * after retries are exhausted. Desugars to a [BoundedTransition] wrapping a [FailureTransition]
 * with a `"failure"` namespace.
 *
 * Example:
 * ```kotlin
 * onFailure retry 3 otherwise "error-handler"
 * ```
 *
 * @property retryCount maximum retry attempts before transitioning to fallback
 * @property rules mutable list to add the compiled transition rule
 */
class RetryBuilder(private val retryCount: Int, private val rules: MutableList<TransitionRule>) {
    /**
     * Specifies the fallback node after retries are exhausted.
     *
     * @param targetNode the node to transition to after all retries fail, not null
     */
    infix fun otherwise(targetNode: String) {
        rules.add(BoundedTransition(FailureTransition(null), "failure", retryCount, targetNode))
    }
}
