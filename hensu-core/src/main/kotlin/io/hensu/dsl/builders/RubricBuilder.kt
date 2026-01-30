package io.hensu.dsl.builders

import io.hensu.dsl.WorkingDirectory

/**
 * DSL builder for registering rubrics within a workflow.
 *
 * Rubrics define quality evaluation criteria for agent outputs. Files are resolved from the
 * `rubrics/` subdirectory of the working directory.
 *
 * Example:
 * ```kotlin
 * rubrics {
 *     rubric("quality", "code-quality.md")        // -> rubrics/code-quality.md
 *     rubric("pr-review", "templates/pr.md")      // -> rubrics/templates/pr.md
 *     rubric("docs") { file = "documentation.md" }
 * }
 * ```
 *
 * @property rubrics mutable map to collect rubric ID to file path mappings
 * @property workingDirectory base directory for rubric file resolution
 * @see RubricRefBuilder for alternative registration syntax
 */
@WorkflowDsl
class RubricRegistryBuilder(
    private val rubrics: MutableMap<String, String>,
    private val workingDirectory: WorkingDirectory,
) {
    /**
     * Registers a rubric file with the given ID.
     *
     * The path is relative to the `rubrics/` directory.
     *
     * @param id unique identifier for referencing this rubric in nodes, not null
     * @param name rubric filename or relative path (with or without .md extension), not null
     * @throws IllegalArgumentException if the rubric file does not exist
     */
    fun rubric(id: String, name: String) {
        val resolvedPath = workingDirectory.resolveRubric(name)
        rubrics[id] = resolvedPath.toString()
    }

    /**
     * Registers a rubric using block syntax.
     *
     * @param id unique identifier for referencing this rubric in nodes, not null
     * @param block configuration block to set the [RubricRefBuilder.file] property
     * @throws IllegalArgumentException if the rubric file does not exist
     */
    fun rubric(id: String, block: RubricRefBuilder.() -> Unit) {
        val builder = RubricRefBuilder()
        builder.apply(block)
        val resolvedPath = workingDirectory.resolveRubric(builder.file)
        rubrics[id] = resolvedPath.toString()
    }
}

/**
 * Builder for rubric file reference in block syntax.
 *
 * Example:
 * ```kotlin
 * rubric("quality") {
 *     file = "quality.md"
 * }
 * ```
 */
@WorkflowDsl
class RubricRefBuilder {
    /** Rubric filename or relative path within the rubrics directory. */
    var file: String = ""
}
