package io.hensu.dsl.builders

import io.hensu.core.execution.parallel.Branch
import io.hensu.core.execution.parallel.ConsensusConfig
import io.hensu.core.execution.parallel.ConsensusStrategy
import io.hensu.core.workflow.node.ParallelNode
import io.hensu.dsl.WorkingDirectory
import io.hensu.dsl.extensions.resolveAsPrompt

/**
 * DSL builder for parallel nodes with consensus-based outcome evaluation.
 *
 * Parallel nodes execute multiple branches concurrently and determine the outcome based on a
 * configurable consensus strategy. Use this for voting, multi-reviewer scenarios, or any case where
 * multiple independent evaluations are needed.
 *
 * Example:
 * ```kotlin
 * parallel("voting") {
 *     branch("reviewer1") {
 *         agent = "reviewer"
 *         prompt = "Review this code: {code}"
 *     }
 *     branch("reviewer2") {
 *         agent = "reviewer"
 *         prompt = "Review this code: {code}"
 *     }
 *
 *     consensus {
 *         strategy = ConsensusStrategy.MAJORITY_VOTE
 *         judge = "senior_reviewer"
 *         threshold = 0.7
 *     }
 *
 *     onConsensus goto "approved"
 *     onNoConsensus goto "needs_review"
 * }
 * ```
 *
 * @property id unique identifier for this parallel node, not null
 * @property workingDirectory base directory for prompt file resolution
 * @see ParallelNode for the compiled node type
 * @see ConsensusStrategy for available consensus strategies
 */
@WorkflowDsl
class ParallelNodeBuilder(private val id: String, private val workingDirectory: WorkingDirectory) :
    BaseNodeBuilder, ConsensusMarkers {
    private val branches = mutableListOf<BranchBuilder>()
    private var consensusConfig: ConsensusConfig? = null
    private val transitionBuilder = TransitionBuilder()

    /**
     * Defines a branch for parallel execution.
     *
     * Each branch executes independently with its own agent and prompt.
     *
     * @param branchId unique identifier for this branch within the parallel node
     * @param block branch configuration block
     */
    fun branch(branchId: String, block: BranchBuilder.() -> Unit) {
        val builder = BranchBuilder(branchId, workingDirectory)
        builder.apply(block)
        branches.add(builder)
    }

    /**
     * Configures consensus evaluation strategy.
     *
     * @param block consensus configuration block
     * @see ConsensusBuilder for available options
     */
    fun consensus(block: ConsensusBuilder.() -> Unit) {
        val builder = ConsensusBuilder()
        builder.apply(block)
        consensusConfig = builder.build()
    }

    /**
     * Defines transition when consensus is reached.
     *
     * Usage: `onConsensus goto "approved"`
     *
     * @param targetNode the node to transition to on consensus, not null
     */
    infix fun onConsensus.goto(targetNode: String) {
        transitionBuilder.addSuccessTransition(targetNode)
    }

    /**
     * Defines transition when consensus is not reached.
     *
     * Usage: `onNoConsensus goto "needs_review"`
     *
     * @param targetNode the node to transition to when no consensus, not null
     */
    infix fun onNoConsensus.goto(targetNode: String) {
        transitionBuilder.addFailureTransition(targetNode)
    }

    /**
     * Defines transition on success (alias for [onConsensus]).
     *
     * @param targetNode the node to transition to on success, not null
     */
    infix fun onSuccess.goto(targetNode: String) {
        transitionBuilder.addSuccessTransition(targetNode)
    }

    /**
     * Defines transition on failure (alias for [onNoConsensus]).
     *
     * @param targetNode the node to transition to on failure, not null
     */
    infix fun onFailure.goto(targetNode: String) {
        transitionBuilder.addFailureTransition(targetNode)
    }

    /**
     * Builds the immutable [ParallelNode] from this builder.
     *
     * @return compiled parallel node, never null
     * @throws IllegalStateException if no branches are defined
     */
    override fun build(): ParallelNode {
        if (branches.isEmpty()) {
            throw IllegalStateException("Parallel node '$id' must have at least one branch")
        }

        val builtBranches = branches.map { it.build() }

        return ParallelNode.builder(id)
            .branches(builtBranches)
            .consensus(consensusConfig)
            .transitionRules(transitionBuilder.build())
            .build()
    }
}

/**
 * DSL builder for a branch within a parallel node.
 *
 * Each branch executes an agent with a prompt independently. Results are collected and evaluated
 * according to the parent node's consensus configuration.
 *
 * @property id unique identifier for this branch
 * @property workingDirectory base directory for prompt file resolution
 */
@WorkflowDsl
class BranchBuilder(private val id: String, private val workingDirectory: WorkingDirectory) {
    /** Agent ID to execute for this branch. Required. */
    var agent: String? = null

    /**
     * Prompt for this branch's agent. Can be inline text or a `.md` file reference. Supports
     * `{variable}` template syntax.
     */
    var prompt: String? = null

    /** Optional rubric ID for evaluating this branch's output. */
    var rubric: String? = null

    /** Weight for weighted voting consensus. Default: 1.0. */
    var weight: Double = 1.0

    /**
     * Builds the [Branch] from this builder.
     *
     * @return compiled branch, never null
     * @throws IllegalStateException if [agent] is not set
     */
    fun build(): Branch =
        Branch(
            id,
            agent ?: throw IllegalStateException("Branch '$id' must have an agent"),
            prompt.resolveAsPrompt(workingDirectory) ?: "",
            rubric,
        )
}

/**
 * DSL builder for consensus evaluation configuration.
 *
 * Configures how branch results are evaluated to determine overall consensus.
 *
 * @see ConsensusStrategy for available strategies
 */
@WorkflowDsl
class ConsensusBuilder {
    /**
     * Strategy for determining consensus from branch results.
     *
     * @see ConsensusStrategy for available options
     */
    var strategy: ConsensusStrategy = ConsensusStrategy.MAJORITY_VOTE

    /**
     * Agent ID for JUDGE_DECIDES strategy.
     *
     * Required when using [ConsensusStrategy.JUDGE_DECIDES], ignored for other strategies.
     */
    var judge: String? = null

    /**
     * Threshold for consensus (interpretation depends on strategy).
     * - MAJORITY_VOTE: Percentage of votes needed (default: 0.5)
     * - WEIGHTED_VOTE: Weighted score threshold (default: 0.5)
     * - UNANIMOUS: Not used
     * - JUDGE_DECIDES: Not used
     */
    var threshold: Double? = null

    /**
     * Builds the [ConsensusConfig] from this builder.
     *
     * @return compiled consensus configuration, never null
     * @throws IllegalStateException if JUDGE_DECIDES strategy is used without a judge agent
     */
    fun build(): ConsensusConfig {
        if (strategy == ConsensusStrategy.JUDGE_DECIDES && judge == null) {
            throw IllegalStateException("JUDGE_DECIDES strategy requires a judge agent")
        }

        return ConsensusConfig(judge, strategy, threshold)
    }
}
