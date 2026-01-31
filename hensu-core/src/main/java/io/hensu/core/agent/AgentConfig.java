package io.hensu.core.agent;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// Immutable configuration for an AI agent.
///
/// Specifies the model, behavioral parameters, and optional features for agent
/// instantiation. Created via the {@link Builder} pattern.
///
/// ### Required Fields
/// - `id` - Unique identifier for registry lookup
/// - `role` - Agent's role description (e.g., "writer", "reviewer")
/// - `model` - AI model identifier (e.g., "claude-sonnet-4", "gpt-4")
///
/// ### Optional Parameters
/// - `temperature` - Sampling temperature (default: 0.7)
/// - `maxTokens` - Maximum response tokens
/// - `tools` - List of tool identifiers the agent can use
/// - `maintainContext` - Whether to preserve conversation context
/// - `instructions` - System-level instructions for the agent
/// - `topP`, `frequencyPenalty`, `presencePenalty` - Model-specific tuning
/// - `timeout` - Request timeout in milliseconds
///
/// @implNote Thread-safe. All fields are immutable after construction.
/// The `tools` list is defensively copied.
///
/// @see Agent for the runtime agent interface
/// @see AgentFactory for creating agents from configurations
public final class AgentConfig {

    private final String id;
    private final String role;
    private final String model;
    private final double temperature;
    private final Integer maxTokens;
    private final List<String> tools;
    private final boolean isMaintainContext;
    private final String instructions;
    private final Double topP;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final Long timeout;

    private AgentConfig(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Agent ID required");
        this.role = Objects.requireNonNull(builder.role, "Role required");
        this.model = Objects.requireNonNull(builder.model, "Model required");
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.tools = Collections.unmodifiableList(builder.tools);
        this.isMaintainContext = builder.isMaintainContext;
        this.instructions = builder.instructions;
        this.topP = builder.topP;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.presencePenalty = builder.presencePenalty;
        this.timeout = builder.timeout;
    }

    /// Returns the unique agent identifier.
    ///
    /// @return non-null identifier used for registry lookup
    public String getId() {
        return id;
    }

    /// Returns the agent's role description.
    ///
    /// @return non-null role (e.g., "writer", "critic", "evaluator")
    public String getRole() {
        return role;
    }

    /// Returns the AI model identifier.
    ///
    /// @return non-null model name (e.g., "claude-sonnet-4", "gpt-4")
    public String getModel() {
        return model;
    }

    /// Returns the sampling temperature for response generation.
    ///
    /// @return temperature value, typically between 0.0 and 2.0 (default: 0.7)
    public Double getTemperature() {
        return temperature;
    }

    /// Returns the maximum number of tokens in the response.
    ///
    /// @return max tokens limit, may be null (provider default used)
    public Integer getMaxTokens() {
        return maxTokens;
    }

    /// Returns the list of tools available to this agent.
    ///
    /// @return unmodifiable list of tool identifiers, never null (may be empty)
    public List<String> getTools() {
        return tools;
    }

    /// Returns whether conversation context should be maintained across calls.
    ///
    /// @return `true` if context should be preserved, `false` for stateless execution
    public boolean isMaintainContext() {
        return isMaintainContext;
    }

    /// Returns system-level instructions for the agent.
    ///
    /// @return instruction text, may be null
    public String getInstructions() {
        return instructions;
    }

    /// Returns the nucleus sampling parameter.
    ///
    /// @return top-p value, may be null (provider default used)
    public Double getTopP() {
        return topP;
    }

    /// Returns the frequency penalty for token repetition.
    ///
    /// @return frequency penalty value, may be null (provider default used)
    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    /// Returns the presence penalty for topic repetition.
    ///
    /// @return presence penalty value, may be null (provider default used)
    public Double getPresencePenalty() {
        return presencePenalty;
    }

    /// Returns the request timeout in milliseconds.
    ///
    /// @return timeout value, may be null (provider default used)
    public Long getTimeout() {
        return timeout;
    }

    /// Creates a new builder for constructing AgentConfig instances.
    ///
    /// @return a new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Builder for constructing immutable {@link AgentConfig} instances.
    ///
    /// @implNote Not thread-safe. Create one builder per thread or synchronize externally.
    public static final class Builder {
        private String id;
        private String role;
        private String model;
        private double temperature = 0.7;
        private Integer maxTokens;
        private List<String> tools = List.of();
        private boolean isMaintainContext = false;
        private String instructions;
        private Double topP;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private Long timeout;

        private Builder() {}

        /// Sets the unique agent identifier.
        ///
        /// @param id the agent ID, not null
        /// @return this builder for chaining
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /// Sets the agent's role description.
        ///
        /// @param role the role description, not null
        /// @return this builder for chaining
        public Builder role(String role) {
            this.role = role;
            return this;
        }

        /// Sets the AI model identifier.
        ///
        /// @param model the model name, not null
        /// @return this builder for chaining
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /// Sets the sampling temperature.
        ///
        /// @param temperature value typically between 0.0 and 2.0
        /// @return this builder for chaining
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /// Sets the maximum response tokens.
        ///
        /// @param maxTokens the token limit, may be null
        /// @return this builder for chaining
        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /// Sets the available tools for this agent.
        ///
        /// @param tools list of tool identifiers, not null
        /// @return this builder for chaining
        public Builder tools(List<String> tools) {
            this.tools = List.copyOf(tools);
            return this;
        }

        /// Sets whether to maintain conversation context.
        ///
        /// @param maintainContext `true` for stateful, `false` for stateless
        /// @return this builder for chaining
        public Builder maintainContext(boolean maintainContext) {
            this.isMaintainContext = maintainContext;
            return this;
        }

        /// Sets system-level instructions.
        ///
        /// @param instructions instruction text, may be null
        /// @return this builder for chaining
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /// Sets the nucleus sampling parameter.
        ///
        /// @param topP the top-p value, may be null
        /// @return this builder for chaining
        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        /// Sets the frequency penalty.
        ///
        /// @param frequencyPenalty the penalty value, may be null
        /// @return this builder for chaining
        public Builder frequencyPenalty(Double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        /// Sets the presence penalty.
        ///
        /// @param presencePenalty the penalty value, may be null
        /// @return this builder for chaining
        public Builder presencePenalty(Double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        /// Sets the request timeout.
        ///
        /// @param timeout timeout in milliseconds, may be null
        /// @return this builder for chaining
        public Builder timeout(Long timeout) {
            this.timeout = timeout;
            return this;
        }

        /// Builds an immutable AgentConfig instance.
        ///
        /// @return the constructed configuration, never null
        /// @throws NullPointerException if id, role, or model is null
        public AgentConfig build() {
            return new AgentConfig(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentConfig that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "AgentConfig{id='" + id + "', model='" + model + "'}";
    }
}
