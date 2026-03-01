package io.hensu.dsl.builders

import io.hensu.core.workflow.node.ForkNode
import io.hensu.core.workflow.node.JoinNode
import io.hensu.core.workflow.node.MergeStrategy

/**
 * DSL builder for fork nodes that spawn parallel execution paths.
 *
 * Fork nodes launch multiple target nodes concurrently using virtual threads. Use [JoinNodeBuilder]
 * to await and merge results from forked executions.
 *
 * Example:
 * ```kotlin
 * fork("parallel-tasks") {
 *     targets("task-a", "task-b", "task-c")
 *     onComplete goto "join-results"
 * }
 * ```
 *
 * @property id unique identifier for this fork node, not null
 * @see JoinNodeBuilder for awaiting forked results
 * @see ForkNode for the compiled node type
 */
@WorkflowDsl
class ForkNodeBuilder(private val id: String) : BaseNodeBuilder, ForkJoinMarkers {
    private val targetList = mutableListOf<String>()
    private val transitionBuilder = TransitionBuilder()
    private var waitForAll = false

    /**
     * Defines target nodes to execute in parallel.
     *
     * @param nodeIds vararg of node IDs to fork to, not null
     */
    fun targets(vararg nodeIds: String) {
        targetList.addAll(nodeIds)
    }

    /**
     * Defines target nodes from a list.
     *
     * @param nodeIds list of node IDs to fork to, not null
     */
    fun targets(nodeIds: List<String>) {
        targetList.addAll(nodeIds)
    }

    /**
     * Whether to wait for all targets before transitioning.
     *
     * Default is false - typically use [JoinNodeBuilder] to explicitly wait and merge results.
     */
    var waitAll: Boolean
        get() = waitForAll
        set(value) {
            waitForAll = value
        }

    /**
     * Defines transition after fork spawns all targets.
     *
     * Usage: `onComplete goto "join-node"`
     *
     * @param targetNode the node to transition to after forking, not null
     */
    infix fun onComplete.goto(targetNode: String) {
        transitionBuilder.addSuccessTransition(targetNode)
    }

    /**
     * Builds the immutable [ForkNode] from this builder.
     *
     * @return compiled fork node, never null
     */
    override fun build(): ForkNode =
        ForkNode.builder(id)
            .targets(targetList)
            .waitForAll(waitForAll)
            .transitionRules(transitionBuilder.build())
            .build()
}

/**
 * DSL builder for join nodes that await and merge forked execution results.
 *
 * Join nodes wait for specified fork nodes to complete, then merge their outputs according to the
 * configured [mergeStrategy]. The merged result is stored in the workflow context under
 * [outputField].
 *
 * Example:
 * ```kotlin
 * join("merge-results") {
 *     await("parallel-tasks")
 *     mergeStrategy = MergeStrategy.COLLECT_ALL
 *     outputField = "merged_output"
 *     timeout = 30000
 *     onSuccess goto "process-results"
 *     onFailure retry 0 otherwise "handle-error"
 * }
 * ```
 *
 * @property id unique identifier for this join node, not null
 * @see ForkNodeBuilder for spawning parallel executions
 * @see JoinNode for the compiled node type
 */
@WorkflowDsl
class JoinNodeBuilder(private val id: String) : BaseNodeBuilder, TransitionMarkers {
    private val awaitList = mutableListOf<String>()
    private val transitionBuilder = TransitionBuilder()

    /**
     * Strategy for combining results from forked executions.
     *
     * @see MergeStrategy for available strategies
     */
    var mergeStrategy: MergeStrategy = MergeStrategy.COLLECT_ALL

    /** Context field name where merged output will be stored. Default: "fork_results". */
    var outputField: String = "fork_results"

    /** Timeout in milliseconds for waiting on forks. 0 means no timeout (wait indefinitely). */
    var timeout: Long = 0

    /** Whether to fail the join if any forked execution fails. Default: true. */
    var failOnError: Boolean = true

    /**
     * Adds fork node IDs to await before proceeding.
     *
     * @param forkNodeIds vararg of fork node IDs to wait for, not null
     */
    fun await(vararg forkNodeIds: String) {
        awaitList.addAll(forkNodeIds)
    }

    /**
     * Adds fork node IDs from a list.
     *
     * @param forkNodeIds list of fork node IDs to wait for, not null
     */
    fun await(forkNodeIds: List<String>) {
        awaitList.addAll(forkNodeIds)
    }

    /**
     * Defines transition on successful join (all awaited forks completed).
     *
     * Usage: `onSuccess goto "next-node"`
     *
     * @param targetNode the node to transition to on success, not null
     */
    infix fun onSuccess.goto(targetNode: String) {
        transitionBuilder.addSuccessTransition(targetNode)
    }

    /**
     * Defines transition on join failure with optional retry.
     *
     * Usage: `onFailure retry 2 otherwise "error-handler"`
     *
     * @param count maximum retry attempts before fallback
     * @return builder for specifying fallback target
     */
    infix fun onFailure.retry(count: Int): RetryBuilder =
        transitionBuilder.createRetryBuilder(count)

    /**
     * Defines a direct failure transition without retry.
     *
     * Usage: `onFailure goto "error-handler"`
     *
     * @param targetNode the node to transition to on failure
     */
    infix fun onFailure.goto(targetNode: String) {
        transitionBuilder.addFailureTransition(targetNode)
    }

    /**
     * Builds the immutable [JoinNode] from this builder.
     *
     * @return compiled join node, never null
     */
    override fun build(): JoinNode =
        JoinNode.builder(id)
            .awaitTargets(awaitList)
            .mergeStrategy(mergeStrategy)
            .outputField(outputField)
            .timeoutMs(timeout)
            .failOnAnyError(failOnError)
            .transitionRules(transitionBuilder.build())
            .build()
}
