package io.hensu.dsl.builders

import io.hensu.core.workflow.state.StateVariableDeclaration
import io.hensu.core.workflow.state.VarType
import io.hensu.core.workflow.state.WorkflowStateSchema

/**
 * DSL builder for declaring the typed state schema of a workflow.
 *
 * Only domain-specific variables need to be declared here. Engine variables (`score`, `approved`)
 * are always implicitly valid.
 *
 * Usage:
 * ```kotlin
 * state {
 *     input("topic", VarType.STRING)
 *     variable("article", VarType.STRING)
 *     variable("recommendation", VarType.STRING)
 * }
 * ```
 *
 * @see WorkflowBuilder.state
 * @see WorkflowStateSchema
 */
@WorkflowDsl
class StateSchemaBuilder {
    internal val declarations: MutableList<StateVariableDeclaration> = mutableListOf()

    /**
     * Declares a workflow input variable — expected to be present in the initial context supplied
     * by the caller.
     *
     * @param name variable identifier, used as `{name}` in prompt templates
     * @param type variable type
     */
    fun input(name: String, type: VarType) {
        declarations.add(StateVariableDeclaration(name, type, true))
    }

    /**
     * Declares a workflow state variable produced by one or more nodes via `writes`.
     *
     * @param name variable identifier, used as `{name}` in prompt templates
     * @param type variable type
     */
    fun variable(name: String, type: VarType) {
        declarations.add(StateVariableDeclaration(name, type, false))
    }

    /**
     * Declares a workflow state variable with a description surfaced in the engine-injected JSON
     * output requirement.
     *
     * The description tells the LLM exactly what content to produce for this field, removing any
     * reliance on semantic inference from the variable name. Prefer this overload whenever the
     * variable name alone is not self-evident.
     *
     * Example:
     * ```kotlin
     * variable("article",  VarType.STRING, "the full written article text")
     * variable("summary",  VarType.STRING, "one-paragraph executive summary for the report")
     * ```
     *
     * @param name variable identifier, used as `{name}` in prompt templates
     * @param type variable type
     * @param description human-readable hint for the LLM describing the expected content
     */
    fun variable(name: String, type: VarType, description: String) {
        declarations.add(StateVariableDeclaration(name, type, false, description))
    }

    internal fun build(): WorkflowStateSchema = WorkflowStateSchema(declarations)
}
