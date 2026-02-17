package io.hensu.dsl.builders

import io.hensu.core.observability.LogLevel
import io.hensu.core.observability.ObservabilityConfig

/**
 * DSL builder for observability configuration.
 *
 * Configures logging and metrics export for workflow execution monitoring.
 *
 * Example:
 * ```kotlin
 * observability {
 *     logLevel = LogLevel.DEBUG
 *     exportMetrics = true
 * }
 * ```
 *
 * @see ObservabilityConfig for the compiled configuration
 */
@WorkflowDsl
class ObservabilityBuilder {
    /** Minimum log level for workflow execution events. Default: [LogLevel.INFO]. */
    var logLevel: LogLevel = LogLevel.INFO

    /** Whether to export execution metrics. Default: true. */
    var exportMetrics: Boolean = true

    /**
     * Builds the immutable [ObservabilityConfig] from this builder.
     *
     * @return compiled observability configuration, never null
     */
    fun build(): ObservabilityConfig = ObservabilityConfig(logLevel, exportMetrics)
}
