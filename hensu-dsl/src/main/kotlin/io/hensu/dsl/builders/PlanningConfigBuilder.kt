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
 * ### Planner Agent
 *
 * Dynamic planning requires an agent to generate the plan. The agent is resolved at execution time
 * from the [io.hensu.core.agent.AgentRegistry] using one of two sources (in priority order):
 * 1. **Explicit** – the [agent] property set in the `planning { }` block.
 * 2. **Fallback** – the node's own `agent` (the same agent that executes steps).
 *
 * If neither is set, execution fails fast with a clear error before the first node runs.
 *
 * Define the planner agent in the workflow's `agents { }` block with the desired model and
 * temperature (e.g. low temperature for structured JSON plan output), then reference it:
 * ```kotlin
 * agents {
 *     agent("planner") {
 *         role = "Plan generation specialist"
 *         model = Models.GEMINI_3_1_FLASH_LITE
 *         temperature = 0.2
 *     }
 *     agent("researcher") {
 *         role = "Deep research analyst"
 *         model = Models.GEMINI_3_1_PRO
 *         temperature = 0.7
 *     }
 * }
 *
 * graph {
 *     node("research") {
 *         agent = "researcher"
 *         planning {
 *             dynamic("planner")   // explicit planner agent
 *             maxSteps = 15
 *             review = true
 *         }
 *         prompt = "Research and analyze topic X"
 *         onSuccess goto "synthesize"
 *     }
 * }
 * ```
 *
 * When the planner agent is omitted, the node's own agent handles both planning and execution:
 * ```kotlin
 * node("analyze") {
 *     agent = "analyst"
 *     planning {
 *         dynamic()               // uses "analyst" for planning too
 *         maxSteps = 5
 *     }
 *     prompt = "Analyze the data"
 *     onSuccess goto "done"
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

    /**
     * Whether to enable review gates before and after plan execution. Default: false.
     *
     * When `true`, pauses for human review both after plan creation (Gate 1) and after plan
     * execution (Gate 2).
     */
    var review: Boolean = false

    /**
     * Identifier of the agent to use for plan generation.
     *
     * When set, overrides the node's own agent for planning purposes. When `null` (default), the
     * node's agent is used as fallback for dynamic planning.
     */
    var agent: String? = null

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
            review,
            agent,
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
     *
     * @param plannerAgent identifier of the agent to use for plan generation; `null` (default)
     *   falls back to the node's own agent
     */
    fun dynamic(plannerAgent: String? = null) {
        mode = PlanningMode.DYNAMIC
        agent = plannerAgent
        allowReplan = true
        maxReplans = 3
        maxDuration = Duration.ofMinutes(5)
        maxTokenBudget = 10000
    }

    /** Enables human review gates (both before and after plan execution). */
    fun withReview() {
        review = true
    }

    /** Disables replanning on step failure. */
    fun noReplan() {
        allowReplan = false
        maxReplans = 0
    }
}
