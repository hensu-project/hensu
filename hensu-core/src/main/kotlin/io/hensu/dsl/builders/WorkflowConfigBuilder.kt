package io.hensu.dsl.builders

import io.hensu.core.workflow.WorkflowConfig
import java.time.Duration

/**
 * DSL builder for workflow execution configuration.
 *
 * Configures runtime behavior including execution limits, checkpointing, and observability.
 *
 * Example:
 * ```kotlin
 * config {
 *     maxExecutionTime = Duration.ofHours(1)
 *     checkpoints = true
 *     checkpointInterval = Duration.ofMinutes(10)
 *     observability {
 *         logLevel = LogLevel.DEBUG
 *     }
 * }
 * ```
 *
 * @see WorkflowConfig for the compiled configuration
 */
@WorkflowDsl
class WorkflowConfigBuilder {
    /** Maximum allowed execution time. Default: 2 hours. */
    private var maxExecutionTime: Duration = Duration.ofHours(2)

    /** Whether to enable execution checkpoints for recovery. Default: false. */
    private var checkpoints: Boolean = false

    /** Interval between automatic checkpoints. Default: 5 minutes. */
    private var checkpointInterval: Duration = Duration.ofMinutes(5)

    private val observabilityBuilder = ObservabilityBuilder()

    /**
     * Configures observability settings (logging, metrics).
     *
     * @param block observability configuration block
     * @see ObservabilityBuilder for available options
     */
    fun observability(block: ObservabilityBuilder.() -> Unit) {
        observabilityBuilder.block()
    }

    /**
     * Builds the immutable [WorkflowConfig] from this builder.
     *
     * @return compiled workflow configuration, never null
     */
    fun build(): WorkflowConfig =
        WorkflowConfig(
            maxExecutionTime.toMillis(),
            checkpoints,
            checkpointInterval.toMillis(),
            observabilityBuilder.build(),
        )
}
