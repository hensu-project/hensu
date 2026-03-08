package io.hensu.dsl.builders

import io.hensu.core.agent.AgentConfig
import io.hensu.core.workflow.Workflow
import io.hensu.core.workflow.WorkflowMetadata
import io.hensu.core.workflow.state.WorkflowStateSchema
import io.hensu.core.workflow.validation.WorkflowValidator
import io.hensu.dsl.WorkingDirectory
import java.time.Instant

/**
 * DSL marker annotation for workflow builder scope control.
 *
 * Prevents implicit receiver leakage between nested DSL blocks.
 */
@DslMarker annotation class WorkflowDsl

/**
 * DSL builder for constructing [Workflow] instances.
 *
 * Provides a fluent API for defining workflow components including agents, rubrics, and the
 * execution graph. The builder compiles to an immutable Java [Workflow] instance.
 *
 * Usage:
 * ```kotlin
 * WorkflowBuilder("my-workflow", workingDir).apply {
 *     description = "My workflow description"
 *     agents { ... }
 *     rubrics { ... }
 *     graph { ... }
 * }.build()
 * ```
 *
 * @property name workflow display name (sanitized for ID)
 * @property workingDirectory base directory for file resolution
 * @see io.hensu.dsl.workflow for the top-level DSL function
 */
@WorkflowDsl
class WorkflowBuilder(private val name: String, private val workingDirectory: WorkingDirectory) {
    var description: String? = null
    var version: String = "1.0.0"
    private val agentConfigs = mutableMapOf<String, AgentConfig>()

    private val rubricPaths = mutableMapOf<String, String>()
    private var graphBuilder: GraphBuilder? = null
    private var configBuilder: WorkflowConfigBuilder? = null
    private var stateSchema: WorkflowStateSchema? = null

    /**
     * Defines agents used by workflow nodes.
     *
     * @param block agent configuration block
     * @see AgentRegistryBuilder for agent definition syntax
     */
    fun agents(block: AgentRegistryBuilder.() -> Unit) {
        val registry = AgentRegistryBuilder(agentConfigs)
        registry.apply(block)
    }

    /**
     * Defines rubrics for quality evaluation.
     *
     * Rubrics are resolved from `workingDirectory/rubrics/`.
     *
     * @param block rubric registration block
     * @see RubricRegistryBuilder for rubric definition syntax
     */
    fun rubrics(block: RubricRegistryBuilder.() -> Unit) {
        val registry = RubricRegistryBuilder(rubricPaths, workingDirectory)
        registry.apply(block)
    }

    /**
     * Defines the workflow execution graph.
     *
     * The graph contains nodes and their transitions. Prompts are resolved from
     * `workingDirectory/prompts/`.
     *
     * @param block graph definition block
     * @see GraphBuilder for node and transition syntax
     */
    fun graph(block: GraphBuilder.() -> Unit) {
        val builder = GraphBuilder(workingDirectory)
        builder.apply(block)
        graphBuilder = builder
    }

    /**
     * Declares the typed state schema for this workflow.
     *
     * Lists all domain-specific variables with their types and input/output roles. Engine variables
     * (`score`, `approved`) are always implicitly valid — do not declare them here. When declared,
     * the schema enables load-time validation of `writes` declarations and prompt template
     * `{variable}` references.
     *
     * @param block schema declaration block
     * @see StateSchemaBuilder for variable declaration syntax
     */
    fun state(block: StateSchemaBuilder.() -> Unit) {
        stateSchema = StateSchemaBuilder().apply(block).build()
    }

    /**
     * Configures workflow execution settings.
     *
     * @param block configuration block
     * @see WorkflowConfigBuilder for available options
     */
    fun config(block: WorkflowConfigBuilder.() -> Unit) {
        val builder = WorkflowConfigBuilder()
        builder.apply(block)
        configBuilder = builder
    }

    /**
     * Builds the immutable [Workflow] instance from DSL configuration.
     *
     * @return compiled workflow ready for execution
     * @throws IllegalStateException if graph is not defined
     */
    fun build(): Workflow {
        val graph =
            graphBuilder?.build()
                ?: throw IllegalStateException("Graph not defined in workflow '$name'")
        val config = configBuilder?.build()

        val workflow =
            Workflow.builder()
                .id(name.sanitizeId())
                .version(version)
                .agents(agentConfigs)
                .rubrics(rubricPaths)
                .nodes(graph.nodes)
                .startNode(graph.startNode)
                .config(config)
                .metadata(WorkflowMetadata(name, description, null, Instant.now(), emptyList()))
                .stateSchema(stateSchema)
                .build()

        WorkflowValidator.validate(workflow)
        return workflow
    }

    private fun String.sanitizeId(): String =
        this.replace(" ", "_").replace(Regex("[^a-zA-Z0-9_-]"), "").lowercase()
}
