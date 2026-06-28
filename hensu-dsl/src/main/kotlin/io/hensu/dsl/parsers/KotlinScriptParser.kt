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

        /**
         * Markers that begin a transition chain. A bare `withFeedback` always punctuates a chain
         * rooted at one of these, so [desugarWithFeedback] wraps from the nearest preceding marker.
         */
        private val TRANSITION_MARKERS =
            listOf(
                "onSuccess",
                "onFailure",
                "onConsensus",
                "onNoConsensus",
                "onComplete",
                "OnPlanFailure",
                "onApproval",
                "onRejection",
                "whenScore",
            )

        /** Matches a standalone `withFeedback` keyword that is not already dotted. */
        private val WITH_FEEDBACK_TOKEN =
            Regex("""(?<![A-Za-z0-9_.])withFeedback(?![A-Za-z0-9_])""")

        /** Matches a standalone transition marker keyword. */
        private val MARKER_TOKEN =
            Regex("""(?<![A-Za-z0-9_.])(${TRANSITION_MARKERS.joinToString("|")})(?![A-Za-z0-9_])""")
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
                "io.hensu.core.workflow.state.VarType",
                "io.hensu.dsl.builders.start",
                "io.hensu.dsl.builders.onSuccess",
                "io.hensu.dsl.builders.onFailure",
                "io.hensu.dsl.builders.onConsensus",
                "io.hensu.dsl.builders.onNoConsensus",
                "io.hensu.dsl.builders.whenScore",
                "io.hensu.dsl.builders.onComplete",
                "io.hensu.dsl.builders.OnPlanFailure",
                "io.hensu.dsl.builders.onApproval",
                "io.hensu.dsl.builders.onRejection",
                "io.hensu.dsl.builders.Models",
                "io.hensu.core.review.ReviewMode",
                "io.hensu.core.execution.result.ExitStatus",
                "io.hensu.core.execution.parallel.ConsensusStrategy",
                "io.hensu.core.plan.PlanningMode",
                "io.hensu.core.workflow.node.MergeStrategy",
                "java.time.Duration",
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

        content = desugarWithFeedback(content)

        return content
    }

    /**
     * Desugars bare `withFeedback` keywords into explicit property access on the preceding
     * transition chain.
     *
     * Kotlin's grammar cannot place a bare trailing identifier in an infix chain: `goto "write"
     * withFeedback` fails to parse because `withFeedback` has neither a following argument (it is a
     * property, not an infix function) nor a leading dot. Postfix `.` access also binds tighter
     * than infix application, so naively dotting the keyword (`goto "write".withFeedback`) would
     * bind the property to the string argument rather than the chain result.
     *
     * The fix wraps the entire chain preceding each `withFeedback` in parentheses, then accesses
     * the property on that parenthesized result. The chain starts at the nearest preceding
     * transition marker (see [TRANSITION_MARKERS]); the keyword is rewritten to `).withFeedback`.
     * Locating the marker by scanning the token stream — rather than the physical line — lets a
     * chain span multiple lines.
     *
     * Scanning is token-aware: a [codeMask] excludes string literals and comments, so an occurrence
     * of `withFeedback` inside a prompt string or comment is never rewritten. Occurrences are
     * processed right-to-left so that two markers on one chain nest correctly: the outer
     * `.withFeedback` wraps the inner one.
     *
     * Examples:
     * ```
     * whenScore lessThan 70.0 goto "write" withFeedback
     *   -> (whenScore lessThan 70.0 goto "write").withFeedback
     *
     * onRejection revise "write" withFeedback retry 3 otherwise "x" withFeedback
     *   -> ((onRejection revise "write").withFeedback retry 3 otherwise "x").withFeedback
     * ```
     */
    private fun desugarWithFeedback(content: String): String {
        var source = content
        while (true) {
            val mask = codeMask(source)

            // Rightmost bare keyword that sits in code (not a string/comment). Rewriting it to
            // `.withFeedback` removes it from the next pass via the not-dotted lookbehind.
            val keyword =
                WITH_FEEDBACK_TOKEN.findAll(source).lastOrNull { mask[it.range.first] } ?: break

            val keywordStart = keyword.range.first
            val keywordEnd = keyword.range.last + 1

            // Collapse the whitespace between the chain and the keyword into the inserted `).`.
            var chainEnd = keywordStart
            while (chainEnd > 0 && source[chainEnd - 1].isWhitespace()) chainEnd--

            // Chain start = nearest preceding marker in code; fall back to line start if absent.
            val chainStart =
                MARKER_TOKEN.findAll(source)
                    .lastOrNull { it.range.first < keywordStart && mask[it.range.first] }
                    ?.range
                    ?.first
                    ?: source.lastIndexOf('\n', keywordStart).let { if (it < 0) 0 else it + 1 }

            source =
                source.substring(0, chainStart) +
                    "(" +
                    source.substring(chainStart, chainEnd) +
                    ").withFeedback" +
                    source.substring(keywordEnd)
        }
        return source
    }

    /**
     * Builds a per-character mask marking which positions are source code rather than string
     * literals or comments.
     *
     * Recognizes line comments (`//`), block comments, single- and triple-quoted string literals
     * (honoring backslash escapes outside triple quotes), and character literals. Characters inside
     * any of these are marked `false`; everything else is `true`. Used by [desugarWithFeedback] to
     * avoid rewriting a `withFeedback` keyword that appears inside a prompt string or comment.
     */
    private fun codeMask(s: String): BooleanArray {
        val code = BooleanArray(s.length)
        val n = s.length
        var i = 0
        while (i < n) {
            val c = s[i]
            when {
                c == '/' && i + 1 < n && s[i + 1] == '/' -> {
                    while (i < n && s[i] != '\n') i++
                }
                c == '/' && i + 1 < n && s[i + 1] == '*' -> {
                    i += 2
                    while (i < n && !(s[i] == '*' && i + 1 < n && s[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' && i + 2 < n && s[i + 1] == '"' && s[i + 2] == '"' -> {
                    i += 3
                    while (i + 2 < n && !(s[i] == '"' && s[i + 1] == '"' && s[i + 2] == '"')) i++
                    i += 3
                }
                c == '"' -> {
                    i++
                    while (i < n && s[i] != '"') {
                        if (s[i] == '\\') i++
                        i++
                    }
                    i++
                }
                c == '\'' -> {
                    i++
                    while (i < n && s[i] != '\'') {
                        if (s[i] == '\\') i++
                        i++
                    }
                    i++
                }
                else -> {
                    code[i] = true
                    i++
                }
            }
        }
        return code
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
