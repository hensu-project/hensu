package io.hensu.dsl

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

/**
 * Encapsulates a structured working directory for Hensu workflows.
 *
 * Provides file resolution for workflow resources including prompts, rubrics, and workflow
 * definitions. All paths are resolved relative to the root directory.
 *
 * Expected structure:
 * ```
 * working-dir/
 * ├── workflows/       # Workflow definitions (.kt files)
 * │   └── custom/      # Optional subdirectories
 * ├── prompts/         # Prompt templates (.md files)
 * │   └── custom/      # Optional subdirectories
 * ├── rubrics/         # Rubric definitions (.md files)
 * │   └── templates/   # Optional subdirectories
 * └── stubs/           # Stub response files (.txt, optional)
 *     └── default/     # Default scenario stubs
 * ```
 *
 * Usage in DSL:
 * - `prompt = "my-prompt.md"` resolves to `prompts/my-prompt.md`
 * - `prompt = "custom/my-prompt.md"` resolves to `prompts/custom/my-prompt.md`
 * - `rubric = "quality.md"` resolves to `rubrics/quality.md`
 *
 * @property root absolute path to the working directory root, never null
 * @see io.hensu.dsl.builders.WorkflowBuilder for DSL usage context
 */
class WorkingDirectory(private val root: Path) {
    companion object {
        /** Subdirectory name for workflow definitions. */
        const val WORKFLOWS_DIR: String = "workflows"

        /** Subdirectory name for prompt templates. */
        const val PROMPTS_DIR: String = "prompts"

        /** Subdirectory name for rubric definitions. */
        const val RUBRICS_DIR: String = "rubrics"

        /** Subdirectory name for stub response files. */
        const val STUBS_DIR: String = "stubs"

        /**
         * Creates a WorkingDirectory from a path, validating existence.
         *
         * @param path path to the working directory, not null
         * @return validated WorkingDirectory instance, never null
         * @throws IllegalArgumentException if path does not exist or is not a directory
         */
        @JvmStatic
        fun of(path: Path): WorkingDirectory {
            require(path.exists()) { "Working directory does not exist: $path" }
            require(path.isDirectory()) { "Path is not a directory: $path" }
            return WorkingDirectory(path.toAbsolutePath())
        }

        /**
         * Creates a WorkingDirectory from a string path.
         *
         * @param path string path to the working directory, not null
         * @return validated WorkingDirectory instance, never null
         * @throws IllegalArgumentException if path does not exist or is not a directory
         */
        @JvmStatic fun of(path: String): WorkingDirectory = of(Path.of(path))
    }

    /** Path to the workflows subdirectory, never null. May not exist on disk. */
    val workflowsDir: Path
        get() = root.resolve(WORKFLOWS_DIR)

    /** Path to the prompts subdirectory, never null. May not exist on disk. */
    val promptsDir: Path
        get() = root.resolve(PROMPTS_DIR)

    /** Path to the rubrics subdirectory, never null. May not exist on disk. */
    val rubricsDir: Path
        get() = root.resolve(RUBRICS_DIR)

    /** Path to the stubs subdirectory, never null. May not exist on disk. */
    val stubsDir: Path
        get() = root.resolve(STUBS_DIR)

    /**
     * Resolves a workflow file path from the workflows directory.
     *
     * Automatically appends `.kt` extension if not present.
     *
     * @param name workflow name (with or without .kt extension), not null
     * @return absolute path to the workflow file, never null
     * @throws IllegalArgumentException if the workflow file does not exist
     */
    fun resolveWorkflow(name: String): Path {
        val fileName = if (name.endsWith(".kt")) name else "$name.kt"
        val path = workflowsDir.resolve(fileName)
        require(path.exists()) { "Workflow not found: $path" }
        return path
    }

    /**
     * Resolves a prompt file and returns its content.
     *
     * Automatically appends `.md` extension if not present. Supports relative paths within the
     * prompts directory (e.g., `custom/my-prompt.md`).
     *
     * @param name prompt name or relative path (with or without .md extension), not null
     * @return content of the prompt file as a string, never null
     * @throws IllegalArgumentException if the prompt file does not exist
     */
    fun resolvePrompt(name: String): String {
        val fileName = if (name.endsWith(".md")) name else "$name.md"
        val path = promptsDir.resolve(fileName)

        if (!path.exists()) {
            throw IllegalArgumentException(
                "Prompt not found: $path (from '$name' in prompts directory)"
            )
        }

        return path.readText()
    }

    /**
     * Resolves a rubric file path from the rubrics directory.
     *
     * Automatically appends `.md` extension if not present. Supports relative paths within the
     * rubrics directory (e.g., `templates/code-quality.md`).
     *
     * @param name rubric name or relative path (with or without .md extension), not null
     * @return absolute path to the rubric file, never null
     * @throws IllegalArgumentException if the rubric file does not exist
     */
    fun resolveRubric(name: String): Path {
        val fileName = if (name.endsWith(".md")) name else "$name.md"
        val path = rubricsDir.resolve(fileName)
        require(path.exists()) { "Rubric not found: $path" }
        return path
    }

    /**
     * Validates that the required directory structure exists.
     *
     * Checks for presence of workflows, prompts, and rubrics subdirectories.
     *
     * @return [ValidationResult.Valid] if all directories exist, [ValidationResult.Invalid] with
     *   list of issues otherwise
     */
    fun validate(): ValidationResult {
        val issues = mutableListOf<String>()

        if (!workflowsDir.exists()) {
            issues.add("Missing workflows directory: $workflowsDir")
        }
        if (!promptsDir.exists()) {
            issues.add("Missing prompts directory: $promptsDir")
        }
        if (!rubricsDir.exists()) {
            issues.add("Missing rubrics directory: $rubricsDir")
        }

        return if (issues.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(issues)
        }
    }

    /**
     * Returns the root path of this working directory.
     *
     * @return absolute path to the root directory, never null
     */
    fun root(): Path = root

    override fun toString(): String = "WorkingDirectory($root)"

    /**
     * Result of directory structure validation.
     *
     * @see validate
     */
    sealed class ValidationResult {
        /** Indicates all required directories exist. */
        data object Valid : ValidationResult()

        /**
         * Indicates one or more required directories are missing.
         *
         * @property issues list of validation error messages, never empty
         */
        data class Invalid(val issues: List<String>) : ValidationResult()
    }
}
