package io.hensu.dsl.builders

import io.hensu.core.agent.AgentConfig

/**
 * DSL builder for registering agents within a workflow.
 *
 * Collects agent definitions that will be available during workflow execution. Each agent is
 * identified by a unique ID and configured with model, role, and parameters.
 *
 * Example:
 * ```kotlin
 * agents {
 *     agent("writer") {
 *         role = "Content Writer"
 *         model = "claude-sonnet-4-5"
 *     }
 *     agent("reviewer") {
 *         role = "Code Reviewer"
 *         model = "gpt-4"
 *         temperature = 0.3
 *     }
 * }
 * ```
 *
 * @property agents mutable map to collect agent configurations, not null
 * @see AgentBuilder for individual agent configuration
 */
@WorkflowDsl
class AgentRegistryBuilder(private val agents: MutableMap<String, AgentConfig>) {
    /**
     * Defines an agent with the given ID.
     *
     * @param id unique identifier for the agent, not null or blank
     * @param block configuration block for agent properties
     * @throws IllegalArgumentException if role or model is blank
     */
    fun agent(id: String, block: AgentBuilder.() -> Unit) {
        val builder = AgentBuilder(id)
        builder.apply(block)
        agents[id] = builder.build()
    }
}

/**
 * DSL builder for configuring an individual AI agent.
 *
 * Agents are the AI model instances that execute workflow node prompts. Each agent requires at
 * minimum a [role] (system prompt context) and [model] (LLM identifier).
 *
 * Example:
 * ```kotlin
 * agent("researcher") {
 *     role = "Research Specialist"
 *     model = "claude-sonnet-4-5"
 *     temperature = 0.7
 *     maxTokens = 4096
 *     maintainContext = true
 *     instructions = "Always cite sources"
 * }
 * ```
 *
 * @property id unique identifier for this agent, not null
 * @see AgentConfig for the compiled configuration
 */
@WorkflowDsl
class AgentBuilder(private val id: String) {
    /** Agent role description used in the system prompt. Required, must not be blank. */
    var role: String = ""

    /** LLM model identifier (e.g., "claude-sonnet-4-5", "gpt-4"). Required, must not be blank. */
    var model: String = ""

    /**
     * Sampling temperature for response randomness. Range: 0.0 (deterministic) to 2.0 (creative).
     */
    var temperature: Double = 0.7

    /** Maximum tokens in the response, may be null to use model default. */
    var maxTokens: Int? = null

    /** List of tool identifiers available to this agent. */
    var tools: List<String> = emptyList()

    /**
     * Whether to maintain conversation context across executions.
     *
     * When true, previous messages are included in subsequent calls, enabling multi-turn
     * conversations within the workflow.
     */
    var maintainContext: Boolean = false

    /**
     * Additional system instructions for the agent, may be null.
     *
     * These are included in the system prompt along with the role, providing extra behavioral
     * guidelines.
     */
    var instructions: String? = null

    /**
     * Top-p (nucleus) sampling parameter, may be null to use model default.
     *
     * Controls diversity by limiting to tokens within cumulative probability p. Typical values:
     * 0.9-1.0. Lower values produce more focused responses.
     */
    var topP: Double? = null

    /**
     * Frequency penalty for token repetition, may be null.
     *
     * Reduces likelihood of repeating tokens based on their frequency in the response.
     * OpenAI/DeepSeek only. Range: -2.0 to 2.0
     */
    var frequencyPenalty: Double? = null

    /**
     * Presence penalty for token repetition, may be null.
     *
     * Reduces likelihood of repeating any tokens that have appeared at all. OpenAI/DeepSeek only.
     * Range: -2.0 to 2.0
     */
    var presencePenalty: Double? = null

    /** Request timeout in seconds, may be null to use default. */
    var timeout: Long? = null

    /**
     * Builds the immutable [AgentConfig] from this builder.
     *
     * @return compiled agent configuration, never null
     * @throws IllegalArgumentException if [role] or [model] is blank
     */
    fun build(): AgentConfig {
        require(role.isNotBlank()) { "Agent role is required" }
        require(model.isNotBlank()) { "Agent model is required" }

        return AgentConfig.builder()
            .id(id)
            .role(role)
            .model(model)
            .temperature(temperature)
            .maxTokens(maxTokens)
            .tools(tools)
            .maintainContext(maintainContext)
            .instructions(instructions)
            .topP(topP)
            .frequencyPenalty(frequencyPenalty)
            .presencePenalty(presencePenalty)
            .timeout(timeout)
            .build()
    }
}
