package io.hensu.dsl.builders

import io.hensu.core.plan.PlanConstraints
import io.hensu.core.plan.PlanningConfig
import io.hensu.core.plan.PlanningMode
import java.time.Duration

/**
 * DSL builder for configuring dynamic planning behavior.
 *
 * Configures how a node generates and executes plans at runtime. Used with the `planning { }` block
 * in node definitions.
 *
 * Example:
 * ```kotlin
 * node("research") {
 *     agent = "gpt-4o"
 *     tools = listOf("search", "analyze", "summarize")
 *     planning {
 *         mode = PlanningMode.DYNAMIC
 *         maxSteps = 15
 *         maxReplans = 5
 *         maxDuration = Duration.ofMinutes(10)
 *         allowReplan = true
 *         reviewBeforeExecute = true
 *     }
 *     prompt = "Research and analyze topic X"
 *     onSuccess goto "synthesize"
 * }
 * ```
 *
 * @see PlanningConfig for the compiled configuration
 * @see PlanningMode for mode options
 */
@WorkflowDsl
class PlanningConfigBuilder {
    /**
     * Planning mode: DISABLED, STATIC, or DYNAMIC.
     * - DISABLED: No planning, direct agent execution
     * - STATIC: Use predefined plan from `plan { }` block
     * - DYNAMIC: LLM generates plan at runtime
     */
    var mode: PlanningMode = PlanningMode.DISABLED

    /** Maximum number of steps allowed in a plan. Default: 10 */
    var maxSteps: Int = 10

    /** Maximum times the plan can be revised on failure. Default: 3 */
    var maxReplans: Int = 3

    /** Whether plan revision is allowed when a step fails. Default: true */
    var allowReplan: Boolean = true

    /** Whether to pause for human review before executing the plan. Default: false */
    var reviewBeforeExecute: Boolean = false

    /** Maximum total execution time. Default: 5 minutes */
    var maxDuration: Duration = Duration.ofMinutes(5)

    /** Maximum LLM tokens for planning (0 = unlimited). Default: 10000 */
    var maxTokenBudget: Int = 10000

    /**
     * Builds the planning configuration.
     *
     * @return new planning config, never null
     */
    fun build(): PlanningConfig =
        PlanningConfig(
            mode,
            PlanConstraints(maxSteps, maxReplans, maxDuration, maxTokenBudget, allowReplan),
            reviewBeforeExecute,
        )

    /**
     * Configures for static planning mode.
     *
     * Sets mode to STATIC with appropriate defaults for predefined plans (no replanning, longer
     * duration).
     */
    fun static() {
        mode = PlanningMode.STATIC
        allowReplan = false
        maxReplans = 0
        maxDuration = Duration.ofMinutes(30)
        maxTokenBudget = 0
    }

    /**
     * Configures for dynamic planning mode.
     *
     * Sets mode to DYNAMIC with default constraints suitable for LLM-generated plans.
     */
    fun dynamic() {
        mode = PlanningMode.DYNAMIC
        allowReplan = true
        maxReplans = 3
        maxDuration = Duration.ofMinutes(5)
        maxTokenBudget = 10000
    }

    /** Enables human review before plan execution. */
    fun withReview() {
        reviewBeforeExecute = true
    }

    /** Disables replanning on step failure. */
    fun noReplan() {
        allowReplan = false
        maxReplans = 0
    }
}
