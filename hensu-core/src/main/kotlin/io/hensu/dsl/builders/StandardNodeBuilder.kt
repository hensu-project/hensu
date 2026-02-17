package io.hensu.dsl.builders

import io.hensu.core.review.ReviewConfig
import io.hensu.core.review.ReviewMode
import io.hensu.core.workflow.node.StandardNode
import io.hensu.dsl.WorkingDirectory
import io.hensu.dsl.extensions.resolveAsPrompt

/**
 * DSL builder for standard workflow nodes.
 *
 * Standard nodes execute an agent with a prompt and transition based on the result. They support
 * rubric evaluation, human review, and output parameter extraction.
 *
 * Example:
 * ```kotlin
 * node("research") {
 *     agent = "researcher"
 *     prompt = "research.md"
 *     rubric = "quality"
 *     outputParams = listOf("findings", "confidence")
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

    /**
     * Parameters to extract from the agent's JSON output. These will be stored in context and can
     * be used in subsequent prompts as {paramName}.
     *
     * Example: outputParams = listOf("lake_name", "population", "capital")
     *
     * The agent should be prompted to output JSON with matching keys.
     */
    var outputParams: List<String> = emptyList()

    private val transitionBuilder = TransitionBuilder()
    private var reviewConfig: ReviewConfig? = null

    /** Define transition on success. Usage: onSuccess goto "next_node" */
    infix fun onSuccess.goto(targetNode: String) {
        transitionBuilder.addSuccessTransition(targetNode)
    }

    /** Define transition on failure with retry. Usage: onFailure retry 3 otherwise "fallback" */
    infix fun onFailure.retry(count: Int): RetryBuilder =
        transitionBuilder.createRetryBuilder(count)

    /**
     * Define score-based transitions. Usage: onScore { whenScore greaterThan 80.0 goto "success"
     * whenScore lessThan 60.0 goto "failure" }
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

    override fun build(): StandardNode =
        StandardNode.builder()
            .id(id)
            .agentId(agent)
            .prompt(prompt.resolveAsPrompt(workingDirectory))
            .rubricId(rubric)
            .reviewConfig(reviewConfig)
            .transitionRules(transitionBuilder.build())
            .outputParams(outputParams)
            .build()
}
