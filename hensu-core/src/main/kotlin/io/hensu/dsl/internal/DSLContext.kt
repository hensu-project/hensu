package io.hensu.dsl.internal

import io.hensu.core.workflow.Workflow
import io.hensu.dsl.WorkingDirectory
import io.hensu.dsl.builders.WorkflowBuilder

/**
 * Internal context for DSL script execution.
 *
 * Provides the `workflow` function within script scope and captures the built workflow for
 * retrieval by the parser. This class is used internally by
 * [io.hensu.dsl.parsers.KotlinScriptParser] and should not be used directly.
 *
 * @property workingDirectory working directory for resolving prompts, rubrics, and other resources
 * @see io.hensu.dsl.parsers.KotlinScriptParser
 */
class DSLContext(val workingDirectory: WorkingDirectory) {
    private var builtWorkflow: Workflow? = null

    /**
     * Defines a workflow within the script context.
     *
     * This function is called from within parsed `.kt` workflow scripts to define the workflow
     * structure.
     *
     * @param name display name for the workflow (sanitized to create ID)
     * @param block workflow configuration block
     * @return the built [Workflow] instance
     */
    fun workflow(name: String, block: WorkflowBuilder.() -> Unit): Workflow {
        val builder = WorkflowBuilder(name, workingDirectory)
        builder.block()
        val workflow = builder.build()
        builtWorkflow = workflow
        return workflow
    }

    /**
     * Retrieves the workflow built by this context.
     *
     * Called by the parser after script execution to extract the result.
     *
     * @return the built workflow
     * @throws IllegalStateException if no workflow was defined in the script
     */
    fun buildWorkflow(): Workflow =
        builtWorkflow ?: throw IllegalStateException("No workflow defined in script")
}
