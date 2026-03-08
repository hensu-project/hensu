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

    internal fun build(): WorkflowStateSchema = WorkflowStateSchema(declarations)
}
