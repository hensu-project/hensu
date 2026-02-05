package io.hensu.dsl.parsers

import io.hensu.core.workflow.Workflow
import io.hensu.dsl.WorkingDirectory
import javax.script.ScriptEngineManager
import kotlin.io.path.readText
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/**
 * Parser for Kotlin DSL workflow scripts.
 *
 * Compiles and evaluates `.kt` workflow definition files into [Workflow] instances. Supports both
 * direct Kotlin scripting (preferred) and JSR-223 fallback for environments with classloader
 * restrictions (e.g., Quarkus).
 *
 * Thread-safety: This class is thread-safe. The companion object maintains shared state for JSR-223
 * detection, protected by volatile access.
 *
 * Example:
 * ```kotlin
 * val parser = KotlinScriptParser()
 * val workingDir = WorkingDirectory.of("working-dir")
 * val workflow = parser.parse(workingDir, "my-workflow")
 * ```
 *
 * @see WorkingDirectory for directory structure requirements
 * @see io.hensu.dsl.internal.DSLContext for script execution context
 */
class KotlinScriptParser {

    companion object {
        /**
         * Flag to track if JSR-223 should be used (for Quarkus compatibility).
         *
         * Once determined, this value is cached to avoid repeated detection overhead. Volatile
         * ensures visibility across threads.
         */
        @Volatile private var useJsr223: Boolean? = null
    }

    /**
     * Parses a workflow by name from a working directory.
     *
     * Resolves the workflow file from `workingDirectory/workflows/` and compiles it.
     *
     * @param workingDirectory the structured working directory, not null
     * @param workflowName name of the workflow (with or without .kt extension), not null
     * @return parsed [Workflow] instance, never null
     * @throws IllegalArgumentException if the workflow file does not exist
     * @throws IllegalStateException if parsing or compilation fails
     */
    fun parse(workingDirectory: WorkingDirectory, workflowName: String): Workflow {
        val workflowPath = workingDirectory.resolveWorkflow(workflowName)
        val scriptContent = workflowPath.readText()
        return parse(scriptContent, workingDirectory)
    }

    /**
     * Parses workflow script content with a specified working directory.
     *
     * Attempts direct Kotlin scripting first, falling back to JSR-223 if module errors are detected
     * (common in Quarkus and other restricted environments).
     *
     * @param scriptContent the Kotlin script source code, not null
     * @param workingDirectory base directory for prompt and rubric resolution, not null
     * @return parsed [Workflow] instance, never null
     * @throws IllegalStateException if parsing fails or script doesn't return a Workflow
     */
    fun parse(scriptContent: String, workingDirectory: WorkingDirectory): Workflow {
        // If we've already determined JSR-223 works, use it directly
        if (useJsr223 == true) {
            return parseViaJsr223(scriptContent, workingDirectory)
        }

        // First try direct compilation (works in normal JVM, tests, etc.)
        return try {
            parseDirectly(scriptContent, workingDirectory)
        } catch (e: IllegalStateException) {
            // If we get module errors, it's likely Quarkus - try JSR-223
            if (
                e.message?.contains("Module") == true && e.message?.contains("module graph") == true
            ) {
                try {
                    val result = parseViaJsr223(scriptContent, workingDirectory)
                    useJsr223 = true // Remember that JSR-223 works
                    result
                } catch (jsr223Error: Exception) {
                    // JSR-223 also failed, show helpful message
                    useJsr223 = false
                    throw IllegalStateException(
                        """
                        |Kotlin scripting failed in this environment.
                        |
                        |WORKAROUND: Use the Gradle task to run workflows directly:
                        |  ./gradlew hensu-dsl:runWorkflow -Pworkflow=<path/to/workflow.kt>
                        |
                        |Original error: ${e.message}
                        |JSR-223 error: ${jsr223Error.message}
                        """
                            .trimMargin(),
                        e,
                    )
                }
            } else {
                throw e
            }
        }
    }

    /**
     * Direct parsing using embedded Kotlin scripting. Works in normal JVM environments but may fail
     * in Quarkus due to classloader issues.
     */
    private fun parseDirectly(scriptContent: String, workingDirectory: WorkingDirectory): Workflow {
        val workflowScript = extractWorkflowContent(scriptContent)
        val executableScript = buildExecutableScript(workflowScript, workingDirectory)

        val compilationConfiguration = ScriptCompilationConfiguration {
            jvm { dependenciesFromCurrentContext(wholeClasspath = true) }
            defaultImports(
                "io.hensu.dsl.builders.start",
                "io.hensu.dsl.builders.onSuccess",
                "io.hensu.dsl.builders.onFailure",
                "io.hensu.dsl.builders.onConsensus",
                "io.hensu.dsl.builders.onNoConsensus",
                "io.hensu.dsl.builders.whenScore",
                "io.hensu.dsl.builders.onComplete",
                "io.hensu.dsl.builders.Models",
                "io.hensu.core.review.ReviewMode",
                "io.hensu.core.execution.result.ExitStatus",
                "io.hensu.core.workflow.node.MergeStrategy",
                "io.hensu.core.execution.parallel.ConsensusStrategy",
            )
        }

        val scriptingHost = BasicJvmScriptingHost()
        val result =
            scriptingHost.eval(
                executableScript.toScriptSource("workflow.kts"),
                compilationConfiguration,
                null,
            )

        return extractWorkflowFromResult(result)
    }

    /**
     * Parse using JSR-223 scripting API. Used as fallback in restricted classloader environments
     * (e.g., Quarkus).
     */
    private fun parseViaJsr223(
        scriptContent: String,
        workingDirectory: WorkingDirectory,
    ): Workflow {
        val workflowScript = extractWorkflowContent(scriptContent)
        val executableScript = buildExecutableScript(workflowScript, workingDirectory)

        val engineManager = ScriptEngineManager(this::class.java.classLoader)
        val engine =
            engineManager.getEngineByExtension("kts")
                ?: throw IllegalStateException(
                    "Kotlin script engine not found. Make sure kotlin-scripting-jsr223 is on the classpath."
                )

        val result = engine.eval(executableScript)

        return result as? Workflow
            ?: throw IllegalStateException(
                "Script did not return a Workflow. Got: ${result?.javaClass?.name}"
            )
    }

    /** Build the executable script string with proper imports and context setup. */
    private fun buildExecutableScript(
        workflowScript: String,
        workingDirectory: WorkingDirectory,
    ): String {
        val workingDirPath =
            workingDirectory.root().toAbsolutePath().toString().replace("\\", "\\\\")

        return """
            import io.hensu.dsl.internal.DSLContext
            import io.hensu.dsl.WorkingDirectory
            import io.hensu.dsl.builders.*
            import io.hensu.core.review.ReviewMode
            import io.hensu.core.execution.result.ExitStatus
            import io.hensu.core.workflow.node.MergeStrategy
            import io.hensu.core.execution.parallel.ConsensusStrategy

            val __workingDir = WorkingDirectory.of("$workingDirPath")
            val __dslContext = DSLContext(__workingDir)

            with(__dslContext) {
                $workflowScript
            }

            __dslContext.buildWorkflow()
        """
            .trimIndent()
    }

    private fun extractWorkflowContent(scriptContent: String): String {
        var content = scriptContent.trim()

        // Skip leading comments (// and /* */) before the function definition
        content = skipLeadingComments(content)

        // Handle function wrappers
        if (content.startsWith("fun ")) {
            // Check for expression-body function: fun foo() = workflow(...)
            val equalsIndex = content.indexOf('=')
            val braceIndex = content.indexOf('{')

            if (equalsIndex != -1 && (braceIndex == -1 || equalsIndex < braceIndex)) {
                // Expression-body function: fun foo() = workflow(...)
                // Extract everything after the '='
                content = content.substring(equalsIndex + 1).trim()
            } else if (braceIndex != -1) {
                // Block-body function: fun foo() { ... }
                content = content.substring(braceIndex + 1)
                content = content.substringBeforeLast('}').trim()
            }
        }

        // Handle import statements - move them outside the with block
        val imports = mutableListOf<String>()
        content =
            content
                .lines()
                .filterNot { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import ")) {
                        imports.add(trimmed)
                        true
                    } else {
                        false
                    }
                }
                .joinToString("\n")

        return content
    }

    /**
     * Skip leading comments before the function definition. Handles:
     * - Single-line comments: // ...
     * - Multi-line comments: /* ... */
     * - KDoc comments: /** ... */
     * - Empty lines
     */
    private fun skipLeadingComments(content: String): String {
        var result = content
        var changed = true

        while (changed) {
            val trimmed = result.trimStart()
            changed = false

            // Skip single-line comments
            if (trimmed.startsWith("//")) {
                val newlineIndex = trimmed.indexOf('\n')
                result =
                    if (newlineIndex != -1) {
                        trimmed.substring(newlineIndex + 1)
                    } else {
                        ""
                    }
                changed = true
                continue
            }

            // Skip multi-line comments (/* ... */ or /** ... */)
            if (trimmed.startsWith("/*")) {
                val endIndex = trimmed.indexOf("*/")
                if (endIndex != -1) {
                    result = trimmed.substring(endIndex + 2)
                    changed = true
                    continue
                }
            }

            result = trimmed
        }

        return result
    }

    private fun extractWorkflowFromResult(
        result: ResultWithDiagnostics<EvaluationResult>
    ): Workflow {
        return when (result) {
            is ResultWithDiagnostics.Success -> {
                when (val returnValue = result.value.returnValue) {
                    is ResultValue.Value -> {
                        returnValue.value as? Workflow
                            ?: throw IllegalStateException(
                                "Script did not return a Workflow. Got: ${returnValue.value?.javaClass?.name}"
                            )
                    }
                    is ResultValue.Unit -> {
                        throw IllegalStateException("Script returned Unit instead of Workflow")
                    }
                    is ResultValue.Error -> {
                        throw IllegalStateException("Script execution error", returnValue.error)
                    }
                    else -> {
                        throw IllegalStateException("Unexpected return value type: $returnValue")
                    }
                }
            }
            is ResultWithDiagnostics.Failure -> {
                val errorMessages =
                    result.reports
                        .filter { it.severity == ScriptDiagnostic.Severity.ERROR }
                        .joinToString("\n") { report ->
                            buildString {
                                append("${report.severity}: ${report.message}")
                                report.location?.let { loc ->
                                    append(" at line ${loc.start.line}, column ${loc.start.col}")
                                }
                            }
                        }
                throw IllegalStateException("Failed to parse Kotlin DSL:\n$errorMessages")
            }
        }
    }
}
