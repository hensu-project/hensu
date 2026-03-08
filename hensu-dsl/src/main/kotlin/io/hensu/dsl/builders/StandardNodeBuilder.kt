package io.hensu.dsl.builders

import io.hensu.core.plan.Plan
import io.hensu.core.plan.PlanningConfig
import io.hensu.core.review.ReviewConfig
import io.hensu.core.review.ReviewMode
import io.hensu.core.workflow.node.StandardNode
import io.hensu.dsl.WorkingDirectory
import io.hensu.dsl.extensions.resolveAsPrompt

/**
 * DSL builder for standard workflow nodes.
 *
 * Standard nodes execute an agent with a prompt and transition based on the result. They support
 * rubric evaluation, human review, and typed state variable output via `writes`.
 *
 * Example:
 * ```kotlin
 * node("research") {
 *     agent = "researcher"
 *     prompt = "research.md"
 *     rubric = "quality"
 *     writes("findings", "confidence")
 *     review(ReviewMode.OPTIONAL)
 *     onSuccess goto "write"
 *     onFailure retry 2 otherwise "error"
 * }
 * ```
 *
 * @property id unique node identifier
 * @property workingDirectory base directory for prompt resolution
 * @see io.hensu.core.workflow.node.StandardNode for compiled node
 */
@WorkflowDsl
class StandardNodeBuilder(private val id: String, private val workingDirectory: WorkingDirectory) :
    BaseNodeBuilder, TransitionMarkers {
    var agent: String? = null

    /**
     * Prompt for the node. Can be either:
     * - Plain text prompt (e.g., "Review this code: {code}")
     * - Name of a prompt file (e.g., "review.md" -> prompts/review.md)
     * - Relative path within prompts (e.g., "custom/review.md" -> prompts/custom/review.md)
     *
     * If the value ends with .md, it will be resolved from the prompts directory.
     */
    var prompt: String? = null
    var rubric: String? = null

    private var writes: List<String> = emptyList()
    private val transitionBuilder = TransitionBuilder()
    private var reviewConfig: ReviewConfig? = null

    // Planning support
    private var staticPlan: Plan? = null
    private var planningConfig: PlanningConfig = PlanningConfig.disabled()
    private var planFailureTarget: String? = null

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

    /** Access to [onApproval] marker for approval transition syntax. */
    @Suppress("RemoveRedundantQualifierName")
    val onApproval: onApproval
        get() = io.hensu.dsl.builders.onApproval

    /** Access to [onRejection] marker for rejection transition syntax. */
    @Suppress("RemoveRedundantQualifierName")
    val onRejection: onRejection
        get() = io.hensu.dsl.builders.onRejection

    /** Define transition on agent approval. Usage: onApproval goto "finalize" */
    infix fun onApproval.goto(targetNode: String) {
        transitionBuilder.addApprovalTransition(true, targetNode)
    }

    /** Define transition on agent rejection. Usage: onRejection goto "improve" */
    infix fun onRejection.goto(targetNode: String) {
        transitionBuilder.addApprovalTransition(false, targetNode)
    }

    /**
     * Declares the semantic state variable names this node produces.
     * - Single name: full text response stored under that variable key.
     * - Multiple names: JSON response parsed, each key extracted to the declared variable.
     *
     * All names must be declared in the workflow `state {}` block or be engine variables (`score`,
     * `approved`). Validated at workflow load time.
     *
     * @param names variable names this node writes to
     */
    fun writes(vararg names: String) {
        writes = names.toList()
    }

    /**
     * Define score-based transitions.
     *
     * Scores are resolved in priority order:
     * 1. Rubric evaluation score (if this node has [rubric] set)
     * 2. Self-reported score from context keys: "score", "final_score", "quality_score",
     *    "evaluation_score" (populated via `writes`)
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

    /** Define human review (simple mode). Usage: review(ReviewMode.REQUIRED) */
    fun review(mode: ReviewMode) {
        reviewConfig = ReviewConfig(mode, false, false)
    }

    /**
     * Define human review (detailed mode). Usage: review { mode = ReviewMode.REQUIRED
     * allowBacktrack = true allowEdit = true }
     */
    fun review(block: ReviewConfigBuilder.() -> Unit) {
        val builder = ReviewConfigBuilder()
        builder.apply(block)
        reviewConfig = builder.build()
    }

    /**
     * Defines a static execution plan for this node.
     *
     * Static plans are predefined sequences of tool invocations that are executed step-by-step
     * without LLM involvement.
     *
     * Example:
     * ```kotlin
     * plan {
     *     step("get_order") {
     *         args("id" to "{orderId}")
     *         description = "Fetch order details"
     *     }
     *     step("validate_order") {
     *         description = "Validate order data"
     *     }
     * }
     * ```
     *
     * @param block configuration block for plan steps
     */
    fun plan(block: PlanBuilder.() -> Unit) {
        val builder = PlanBuilder()
        builder.apply(block)
        staticPlan = builder.build(id)
        planningConfig = PlanningConfig.forStatic()
    }

    /**
     * Configures dynamic planning behavior for this node.
     *
     * Dynamic planning uses an LLM to generate execution plans at runtime based on the node's goal
     * and available tools.
     *
     * Example:
     * ```kotlin
     * planning {
     *     mode = PlanningMode.DYNAMIC
     *     maxSteps = 15
     *     maxReplans = 5
     *     allowReplan = true
     *     review = true
     * }
     * ```
     *
     * @param block configuration block for planning settings
     */
    fun planning(block: PlanningConfigBuilder.() -> Unit) {
        val builder = PlanningConfigBuilder()
        builder.apply(block)
        planningConfig = builder.build()
    }

    /** Marker for plan failure transitions. */
    val onPlanFailure: OnPlanFailure
        get() = OnPlanFailure

    /** Define transition on plan failure. Usage: onPlanFailure goto "error-handler" */
    infix fun OnPlanFailure.goto(targetNode: String) {
        planFailureTarget = targetNode
    }

    override fun build(): StandardNode =
        StandardNode.builder()
            .id(id)
            .agentId(agent)
            .prompt(prompt.resolveAsPrompt(workingDirectory))
            .rubricId(rubric)
            .reviewConfig(reviewConfig)
            .transitionRules(transitionBuilder.build())
            .writes(writes)
            .planningConfig(planningConfig)
            .staticPlan(staticPlan)
            .planFailureTarget(planFailureTarget)
            .build()
}
