package io.hensu.dsl.builders

import io.hensu.core.plan.Plan
import io.hensu.core.plan.PlannedStep

/**
 * DSL builder for defining static execution plans within nodes.
 *
 * Static plans define a sequence of tool invocations at compile time. The plan is executed
 * step-by-step, with each step invoking a tool with specified arguments.
 *
 * Example:
 * ```kotlin
 * node("process-order") {
 *     plan {
 *         step("get_order") {
 *             args("id" to "{orderId}")
 *             description = "Fetch order details"
 *         }
 *         step("validate_order") {
 *             description = "Validate order data"
 *         }
 *         step("process_payment") {
 *             args("amount" to "{orderTotal}", "currency" to "USD")
 *             description = "Process payment"
 *         }
 *     }
 *     onSuccess goto "confirmation"
 * }
 * ```
 *
 * @see Plan for the compiled plan structure
 * @see PlannedStep for individual step details
 */
@WorkflowDsl
class PlanBuilder {
    private val steps = mutableListOf<PlannedStep>()

    /**
     * Adds a step to the plan using a builder block.
     *
     * @param toolName the tool to invoke, not blank
     * @param block configuration block for step details
     */
    fun step(toolName: String, block: StepBuilder.() -> Unit = {}) {
        val builder = StepBuilder(steps.size, toolName)
        builder.apply(block)
        steps.add(builder.build())
    }

    /**
     * Adds a simple step with just a tool name.
     *
     * @param toolName the tool to invoke, not blank
     * @param description human-readable description
     */
    fun step(toolName: String, description: String) {
        steps.add(PlannedStep.simple(steps.size, toolName, description))
    }

    /**
     * Builds the plan with default constraints.
     *
     * @return new static plan, never null
     */
    fun build(): Plan = Plan.staticPlan("", steps.toList())

    /**
     * Builds the plan for a specific node.
     *
     * @param nodeId the owning node's ID
     * @return new static plan, never null
     */
    fun build(nodeId: String): Plan = Plan.staticPlan(nodeId, steps.toList())
}

/**
 * DSL builder for individual plan steps.
 *
 * Configures a single step's tool invocation including arguments and description.
 *
 * @property index step position in plan (zero-based)
 * @property toolName tool to invoke
 */
@WorkflowDsl
class StepBuilder(private val index: Int, private val toolName: String) {
    private val arguments = mutableMapOf<String, Any>()

    /** Human-readable description of what this step does. */
    var description: String = ""

    /**
     * Sets arguments for the tool invocation.
     *
     * Arguments can contain `{variable}` placeholders that are resolved from workflow context at
     * execution time.
     *
     * @param pairs key-value pairs of arguments
     */
    fun args(vararg pairs: Pair<String, Any>) {
        arguments.putAll(pairs)
    }

    /**
     * Sets a single argument.
     *
     * @param key argument name
     * @param value argument value (may contain placeholders)
     */
    fun arg(key: String, value: Any) {
        arguments[key] = value
    }

    /**
     * Builds the planned step.
     *
     * @return new planned step, never null
     */
    fun build(): PlannedStep = PlannedStep.pending(index, toolName, arguments.toMap(), description)
}
