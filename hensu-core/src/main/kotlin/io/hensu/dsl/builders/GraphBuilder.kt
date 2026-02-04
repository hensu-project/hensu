package io.hensu.dsl.builders

import io.hensu.core.execution.result.ExitStatus
import io.hensu.core.workflow.node.Node
import io.hensu.dsl.WorkingDirectory

/**
 * DSL builder for workflow execution graphs.
 *
 * Defines the node structure and transitions of a workflow. Supports various node types including
 * standard (agent-based), action (command execution), parallel (concurrent branches), generic
 * (custom handlers), fork/join, and end nodes.
 *
 * Example:
 * ```kotlin
 * graph {
 *     start at "develop"
 *
 *     node("develop") {
 *         agent = "coder"
 *         prompt = "Implement feature"
 *         onSuccess goto "commit"
 *     }
 *
 *     action("commit") {
 *         execute("git-commit")
 *         onSuccess goto "end"
 *     }
 *
 *     end("end")
 * }
 * ```
 *
 * @property workingDirectory base directory for prompt file resolution
 * @see StandardNodeBuilder for standard node configuration
 * @see ActionNodeBuilder for action node configuration
 * @see ParallelNodeBuilder for concurrent execution
 */
@WorkflowDsl
class GraphBuilder(private val workingDirectory: WorkingDirectory) {
    private var startNodeId: String? = null
    private val nodeBuilders = mutableMapOf<String, BaseNodeBuilder>()

    /**
     * Sets the workflow entry point node.
     *
     * Usage: `start at "node_id"`
     *
     * @param nodeId the ID of the first node to execute
     */
    infix fun start.at(nodeId: String) {
        startNodeId = nodeId
    }

    /** Enables `start at "nodeId"` syntax. */
    val start: start
        get() = io.hensu.dsl.builders.start

    /**
     * Defines a standard node for agent-based execution.
     *
     * @param id unique node identifier
     * @param block node configuration block
     * @see StandardNodeBuilder for configuration options
     */
    fun node(id: String, block: StandardNodeBuilder.() -> Unit) {
        val builder = StandardNodeBuilder(id, workingDirectory)
        builder.apply(block)
        nodeBuilders[id] = builder
    }

    /**
     * Define a parallel node for concurrent execution with consensus.
     *
     * Usage:
     * ```
     * parallel("voting") {
     *     branch("reviewer1") {
     *         agent = "reviewer"
     *         prompt = "Review: {code}"
     *     }
     *     branch("reviewer2") {
     *         agent = "reviewer"
     *         prompt = "Review: {code}"
     *     }
     *
     *     consensus {
     *         strategy = ConsensusStrategy.MAJORITY_VOTE
     *     }
     *
     *     onConsensus goto "approved"
     *     onNoConsensus goto "rejected"
     * }
     * ```
     */
    fun parallel(id: String, block: ParallelNodeBuilder.() -> Unit) {
        val builder = ParallelNodeBuilder(id, workingDirectory)
        builder.apply(block)
        nodeBuilders[id] = builder
    }

    /**
     * Define a generic node for custom execution logic.
     *
     * Generic nodes delegate to user-registered handlers identified by executorType. The handler
     * must be registered before workflow execution:
     * ```kotlin
     * registry.registerGenericHandler("validator") { node, context ->
     *     // Custom logic here
     *     NodeResult.success("Result", mapOf())
     * }
     * ```
     *
     * Usage:
     * ```kotlin
     * generic("validate") {
     *     executorType = "validator"
     *     config {
     *         "minLength" to 10
     *     }
     *     onSuccess goto "next"
     *     onFailure retry 2 otherwise "error"
     * }
     * ```
     */
    fun generic(id: String, block: GenericNodeBuilder.() -> Unit) {
        val builder = GenericNodeBuilder(id)
        builder.apply(block)
        nodeBuilders[id] = builder
    }

    /**
     * Define a fork node for parallel execution of multiple targets.
     *
     * Fork spawns virtual threads to execute target nodes concurrently. Use join() to await and
     * merge results.
     *
     * Usage:
     * ```kotlin
     * fork("parallel-tasks") {
     *     targets("process-a", "process-b", "process-c")
     *     onComplete goto "join-results"
     * }
     * ```
     */
    fun fork(id: String, block: ForkNodeBuilder.() -> Unit) {
        val builder = ForkNodeBuilder(id)
        builder.apply(block)
        nodeBuilders[id] = builder
    }

    /**
     * Define a join node to await forked executions and merge results.
     *
     * Join waits for specified fork nodes to complete, then merges their outputs according to the
     * configured strategy.
     *
     * Usage:
     * ```kotlin
     * join("merge-results") {
     *     await("parallel-tasks")
     *     mergeStrategy = MergeStrategy.COLLECT_ALL
     *     outputField = "merged_output"
     *     onSuccess goto "process-merged"
     *     onFailure retry 0 otherwise "error"
     * }
     * ```
     */
    fun join(id: String, block: JoinNodeBuilder.() -> Unit) {
        val builder = JoinNodeBuilder(id)
        builder.apply(block)
        nodeBuilders[id] = builder
    }

    /**
     * Define an end node marking workflow completion.
     *
     * Usage:
     * ```kotlin
     * end("success")
     * end("failure", ExitStatus.FAILURE)
     * ```
     */
    fun end(id: String, status: ExitStatus = ExitStatus.SUCCESS) {
        nodeBuilders[id] = EndNodeBuilder(id, status)
    }

    /**
     * Define an action node for executing commands mid-workflow.
     *
     * Action nodes execute side effects (shell commands, messaging, HTTP calls) and then transition
     * to the next node. Unlike end nodes, action nodes continue workflow execution.
     *
     * Usage:
     * ```kotlin
     * action("commit") {
     *     execute("git-commit")
     *     onSuccess goto "pr"
     * }
     *
     * action("notify-team") {
     *     send("slack", mapOf("message" to "Build completed: {result}"))
     *     send("webhook", mapOf("event" to "deploy"))
     *     onSuccess goto "deploy"
     *     onFailure retry 2 otherwise "error"
     * }
     * ```
     *
     * @param id unique node identifier
     * @param block action configuration block
     * @see ActionNodeBuilder for configuration options
     */
    fun action(id: String, block: ActionNodeBuilder.() -> Unit) {
        val builder = ActionNodeBuilder(id)
        builder.apply(block)
        nodeBuilders[id] = builder
    }

    /**
     * Builds the graph result from configured nodes.
     *
     * @return compiled graph with nodes and start node
     * @throws IllegalStateException if start node is not defined
     */
    fun build(): GraphBuildResult {
        val nodes = nodeBuilders.mapValues { it.value.build() }
        val start = startNodeId ?: throw IllegalStateException("Start node not defined")

        return GraphBuildResult(nodes = nodes, startNode = start)
    }
}

/**
 * Result of graph compilation.
 *
 * @property nodes map of node ID to compiled node instances
 * @property startNode ID of the entry point node
 */
data class GraphBuildResult(val nodes: Map<String, Node>, val startNode: String)
