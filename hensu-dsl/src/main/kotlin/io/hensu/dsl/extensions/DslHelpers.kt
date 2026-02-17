package io.hensu.dsl.extensions

import io.hensu.core.rubric.model.DoubleRange
import io.hensu.core.state.HensuState
import io.hensu.core.workflow.Workflow
import io.hensu.core.workflow.node.StandardNode
import io.hensu.dsl.WorkingDirectory
import java.time.Duration

/**
 * Kotlin extension functions and utilities for the Hensu DSL.
 *
 * Provides convenience extensions for:
 * - Prompt resolution (file vs inline)
 * - Duration literals (`5.minutes`, `2.hours`)
 * - Type-safe state access
 * - Workflow and node property shortcuts
 * - Range conversions between Kotlin and Java types
 */

/**
 * Checks if this string represents a Markdown file reference.
 *
 * @return true if the string ends with `.md` (case-insensitive)
 */
fun String.isMarkdownFile(): Boolean = endsWith(".md", ignoreCase = true)

/**
 * Resolves a prompt value from the working directory if it's a file reference.
 *
 * If the string ends with `.md`, resolves it from the prompts directory. Otherwise, returns the
 * string as-is (inline prompt).
 *
 * @param workingDirectory base directory for prompt file resolution
 * @return resolved prompt content, or null if receiver is null
 * @throws IllegalArgumentException if the prompt file does not exist
 */
fun String?.resolveAsPrompt(workingDirectory: WorkingDirectory): String? {
    if (this == null) return null
    return if (isMarkdownFile()) {
        workingDirectory.resolvePrompt(this)
    } else {
        this
    }
}

// Duration extensions for readable syntax

/** Converts this integer to a [Duration] of seconds. */
val Int.seconds: Duration
    get() = Duration.ofSeconds(this.toLong())

/** Converts this integer to a [Duration] of minutes. */
val Int.minutes: Duration
    get() = Duration.ofMinutes(this.toLong())

/** Converts this integer to a [Duration] of hours. */
val Int.hours: Duration
    get() = Duration.ofHours(this.toLong())

/** Converts this integer to a [Duration] of days. */
val Int.days: Duration
    get() = Duration.ofDays(this.toLong())

// State extensions

/**
 * Gets a value from the state context by key.
 *
 * @param key the context key
 * @return the value, or null if not present
 */
fun HensuState.get(key: String): Any? = context[key]

/**
 * Gets a string value from the state context.
 *
 * @param key the context key
 * @return the value as string, or null if not present
 */
fun HensuState.getString(key: String): String? = context[key]?.toString()

/**
 * Gets an integer value from the state context.
 *
 * @param key the context key
 * @return the value as Int, or null if not present or wrong type
 */
fun HensuState.getInt(key: String): Int? = context[key] as? Int

/**
 * Gets a double value from the state context.
 *
 * @param key the context key
 * @return the value as Double, or null if not present or wrong type
 */
fun HensuState.getDouble(key: String): Double? = context[key] as? Double

/**
 * Gets a boolean value from the state context.
 *
 * @param key the context key
 * @return the value as Boolean, or null if not present or wrong type
 */
fun HensuState.getBoolean(key: String): Boolean? = context[key] as? Boolean

// Workflow extensions

/** Returns the number of nodes in this workflow. */
val Workflow.nodeCount: Int
    get() = nodes.size

/** Returns the number of agents defined in this workflow. */
val Workflow.agentCount: Int
    get() = agents.size

/** Returns true if this workflow has any rubrics defined. */
val Workflow.hasRubrics: Boolean
    get() = rubrics.isNotEmpty()

// Node extensions

/** Returns true if this node has an agent assigned. */
val StandardNode.hasAgent: Boolean
    get() = agentId != null

/** Returns true if this node has a rubric for quality evaluation. */
val StandardNode.hasRubric: Boolean
    get() = rubricId != null

/** Returns true if this node has human review configured. */
val StandardNode.hasReview: Boolean
    get() = reviewConfig != null

/**
 * Converts Kotlin's [ClosedFloatingPointRange] to Java [DoubleRange].
 *
 * Used for score condition range comparisons in the DSL.
 */
val ClosedFloatingPointRange<Double>.asJavaRange: DoubleRange
    get() = DoubleRange(this.start, this.endInclusive)

/** Converts Java [DoubleRange] back to Kotlin's [ClosedFloatingPointRange]. */
val DoubleRange.asKotlinRange: ClosedFloatingPointRange<Double>
    get() = start()..end()

/**
 * Applies the block only if the condition is true.
 *
 * @param condition whether to apply the block
 * @param block configuration block to conditionally apply
 * @return this instance for chaining
 */
inline fun <T> T.applyIf(condition: Boolean, block: T.() -> Unit): T {
    if (condition) {
        block()
    }
    return this
}

/**
 * Executes the block if this value is not null.
 *
 * @param block transformation to apply to the non-null value
 * @return result of the block, or null if receiver is null
 */
inline fun <T, R> T?.ifNotNull(block: (T) -> R): R? {
    return this?.let(block)
}
