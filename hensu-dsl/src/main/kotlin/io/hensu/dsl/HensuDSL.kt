package io.hensu.dsl

import io.hensu.core.workflow.Workflow
import io.hensu.dsl.builders.WorkflowBuilder

/**
 * Creates a workflow using the Kotlin DSL.
 *
 * The DSL provides a type-safe, declarative way to define workflows with agents, nodes,
 * transitions, and rubrics. Prompts and rubrics are resolved from the working directory's
 * `prompts/` and `rubrics/` subdirectories.
 *
 * Example:
 * ```kotlin
 * val workingDir = WorkingDirectory.of("working-dir")
 * val workflow = workflow("MyWorkflow", workingDir) {
 *     description = "Example workflow"
 *
 *     agents {
 *         agent("researcher") {
 *             role = "Research Specialist"
 *             model = "claude-sonnet-4-5"
 *         }
 *     }
 *
 *     graph {
 *         start at "research"
 *
 *         node("research") {
 *             agent = "researcher"
 *             prompt = "research.md"  // Resolved from prompts/research.md
 *             onSuccess goto "end"
 *         }
 *
 *         end("end")
 *     }
 * }
 * ```
 *
 * @param name the workflow display name (sanitized to create ID)
 * @param workingDirectory directory for prompt and rubric resolution
 * @param block DSL configuration block
 * @return immutable [Workflow] instance ready for execution
 * @throws IllegalStateException if graph is not defined
 */
fun workflow(
    name: String,
    workingDirectory: WorkingDirectory,
    block: WorkflowBuilder.() -> Unit,
): Workflow {
    val builder = WorkflowBuilder(name, workingDirectory)
    builder.apply(block)
    return builder.build()
}
