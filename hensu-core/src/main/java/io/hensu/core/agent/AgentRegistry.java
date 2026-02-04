package io.hensu.core.agent;

import java.util.Map;
import java.util.Optional;

/// Registry for managing agent instances within a workflow execution environment.
///
/// Provides thread-safe storage and retrieval of {@link Agent} instances by their
/// unique identifiers. Agents are created on-demand from {@link AgentConfig}
/// using the underlying {@link AgentFactory}.
///
/// ### Contracts
/// - **Precondition**: Agent IDs must be non-null and non-blank
/// - **Postcondition**: Registered agents are immediately available for retrieval
/// - **Invariant**: Registry state is always consistent (no partial registrations)
///
/// @implNote Implementations must be thread-safe. Concurrent workflows may
/// register and retrieve agents simultaneously.
///
/// @see DefaultAgentRegistry for the standard implementation
/// @see AgentFactory for agent creation mechanics
public interface AgentRegistry {

    /// Retrieves an agent by its unique identifier.
    ///
    /// @param id the agent identifier to look up, not null
    /// @return an {@link Optional} containing the agent if found, empty otherwise
    /// @throws NullPointerException if id is null
    Optional<Agent> getAgent(String id);

    /// Retrieves an agent by ID or throws an exception if not found.
    ///
    /// @param id the agent identifier to look up, not null
    /// @return the agent instance, never null
    /// @throws AgentNotFoundException if no agent exists with the given ID
    /// @throws NullPointerException if id is null
    Agent getAgentOrThrow(String id) throws AgentNotFoundException;

    /// Creates and registers an agent with the given configuration.
    ///
    /// If an agent with the same ID already exists, it will be replaced.
    ///
    /// @apiNote **Side effects**:
    /// - Modifies internal agent registry
    /// - Overwrites existing agent if ID already registered
    /// - Logs registration at INFO level
    ///
    /// @param agentId unique identifier for the agent, not null or blank
    /// @param config agent configuration specifying model and parameters, not null
    /// @return the created and registered agent, never null
    /// @throws NullPointerException if agentId or config is null
    /// @throws IllegalArgumentException if agentId is blank
    /// @throws IllegalStateException if no provider supports the configured model
    Agent registerAgent(String agentId, AgentConfig config);

    /// Registers multiple agents from a configuration map.
    ///
    /// Equivalent to calling {@link #registerAgent(String, AgentConfig)} for each entry.
    ///
    /// @apiNote **Side effects**:
    /// - Modifies internal agent registry
    /// - Overwrites existing agents if IDs already registered
    ///
    /// @param configs map of agent ID to configuration, not null (may be empty)
    /// @throws NullPointerException if configs is null
    /// @throws IllegalStateException if any configured model is unsupported
    void registerAgents(Map<String, AgentConfig> configs);

    /// Checks whether an agent with the given ID is registered.
    ///
    /// @param agentId the agent identifier to check, not null
    /// @return `true` if an agent with this ID exists, `false` otherwise
    /// @throws NullPointerException if agentId is null
    boolean hasAgent(String agentId);
}
