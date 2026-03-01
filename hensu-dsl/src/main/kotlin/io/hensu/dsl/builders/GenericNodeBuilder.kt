package io.hensu.dsl.builders

import io.hensu.core.workflow.node.GenericNode
import io.hensu.core.workflow.node.Node

/**
 * Builder for generic nodes that allow custom execution logic.
 *
 * Generic nodes delegate to user-registered handlers identified by [executorType]. Multiple generic
 * nodes can share the same handler or have unique handlers.
 *
 * Usage:
 * ```kotlin
 * generic("validate-input") {
 *     executorType = "validator"  // Identifies which handler to use
 *
 *     config {
 *         "minLength" to 10
 *         "maxLength" to 1000
 *         "allowEmpty" to false
 *     }
 *
 *     onSuccess goto "process"
 *     onFailure retry 2 otherwise "error"
 * }
 * ```
 *
 * Register handlers before workflow execution:
 * ```kotlin
 * registry.registerGenericHandler("validator") { node, context ->
 *     val input = context.state.context["input"] as String
 *     val minLength = node.config["minLength"] as? Int ?: 0
 *
 *     if (input.length >= minLength) {
 *         NodeResult.success("Valid", mapOf("validated" to true))
 *     } else {
 *         NodeResult.failure("Input too short")
 *     }
 * }
 * ```
 */
@WorkflowDsl
class GenericNodeBuilder(private val id: String) : BaseNodeBuilder, TransitionMarkers {
    /**
     * The executor type identifier used to look up the handler. Multiple nodes can share the same
     * type (same handler) or use unique types.
     */
    var executorType: String? = null

    /** Optional rubric ID for quality evaluation. */
    var rubric: String? = null

    private val configMap = mutableMapOf<String, Any>()
    private val transitionBuilder = TransitionBuilder()

    /**
     * Define configuration parameters for this node. These are passed to the handler at execution
     * time.
     *
     * Usage:
     * ```kotlin
     * config {
     *     "key1" to "value1"
     *     "key2" to 42
     *     "key3" to listOf("a", "b", "c")
     * }
     * ```
     */
    fun config(block: ConfigBuilder.() -> Unit) {
        val builder = ConfigBuilder()
        builder.apply(block)
        configMap.putAll(builder.entries)
    }

    /** Define transition on success. Usage: onSuccess goto "next_node" */
    infix fun onSuccess.goto(targetNode: String) {
        transitionBuilder.addSuccessTransition(targetNode)
    }

    /** Define direct failure transition without retry. Usage: onFailure goto "error-node" */
    infix fun onFailure.goto(targetNode: String) {
        transitionBuilder.addFailureTransition(targetNode)
    }

    /** Define transition on failure with retry. Usage: onFailure retry 3 otherwise "fallback" */
    infix fun onFailure.retry(count: Int): RetryBuilder =
        transitionBuilder.createRetryBuilder(count)

    /**
     * Define score-based transitions.
     *
     * Scores are resolved in priority order:
     * 1. Rubric evaluation score (if this node has [rubric] set)
     * 2. Self-reported score from context keys: "score", "final_score", "quality_score",
     *    "evaluation_score"
     *
     * When a node has both a rubric and onScore transitions, the onScore transitions take
     * precedence over auto-backtrack for failed rubrics.
     *
     * Usage:
     * ```kotlin
     * onScore {
     *     whenScore greaterThan 80.0 goto "success"
     *     whenScore lessThan 60.0 goto "failure"
     * }
     * ```
     */
    fun onScore(block: ScoreTransitionBuilder.() -> Unit) {
        transitionBuilder.onScore(block)
    }

    override fun build(): Node {
        requireNotNull(executorType) { "Generic node '$id' requires executorType to be set" }

        return GenericNode.builder()
            .id(id)
            .executorType(executorType)
            .config(configMap)
            .rubricId(rubric)
            .transitionRules(transitionBuilder.build())
            .build()
    }
}

/** Builder for configuration map entries. */
@WorkflowDsl
class ConfigBuilder {
    internal val entries = mutableMapOf<String, Any>()

    /** Add a configuration entry. Usage: "key" to value */
    infix fun String.to(value: Any) {
        entries[this] = value
    }
}
