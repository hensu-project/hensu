package io.hensu.core.agent;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/// Default implementation of {@link AgentRegistry} with thread-safe agent management.
///
/// Stores agent instances in a {@link ConcurrentHashMap} and delegates agent creation
/// to the provided {@link AgentFactory}. Supports concurrent registration and retrieval
/// from multiple workflow threads.
///
/// @implNote Thread-safe. Uses {@link ConcurrentHashMap} for agent storage.
/// All public methods can be called from any thread without external synchronization.
///
/// @see AgentRegistry for the interface contract
/// @see AgentFactory for agent creation
public class DefaultAgentRegistry implements AgentRegistry {

    private static final Logger logger = Logger.getLogger(DefaultAgentRegistry.class.getName());

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    private final AgentFactory agentFactory;

    /// Creates a new registry backed by the given agent factory.
    ///
    /// @param agentFactory factory for creating agents from configurations, not null
    /// @throws NullPointerException if agentFactory is null
    public DefaultAgentRegistry(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    @Override
    public Optional<Agent> getAgent(String agentId) {
        Agent agent = agents.get(agentId);
        if (agent == null) {
            logger.warning("Agent not found: " + agentId);
            return Optional.empty();
        }
        return Optional.of(agent);
    }

    @Override
    public Agent getAgentOrThrow(String id) throws AgentNotFoundException {
        return getAgent(id).orElseThrow(() -> new AgentNotFoundException("Agent not found: " + id));
    }

    /// Creates and registers an agent with the given configuration.
    ///
    /// If an agent with the same ID already exists, it will be replaced and a
    /// warning logged.
    ///
    /// @apiNote **Side effects**:
    /// - Modifies internal agent registry
    /// - Overwrites existing agent if ID already registered
    /// - Logs registration at INFO level
    /// - Logs warning if replacing existing agent
    ///
    /// @param agentId unique identifier for the agent, not null
    /// @param config agent configuration specifying model and parameters, not null
    /// @return the created and registered agent, never null
    /// @throws NullPointerException if agentId or config is null
    /// @throws IllegalStateException if no provider supports the configured model
    @Override
    public Agent registerAgent(String agentId, AgentConfig config) {
        if (agents.containsKey(agentId)) {
            logger.warning("Agent already exists: " + agentId + ". Replacing...");
        }

        Agent agent = agentFactory.createAgent(agentId, config);
        agents.put(agentId, agent);

        logger.info("Registered agent: " + agentId + " with model: " + config.getModel());
        return agent;
    }

    /// Registers multiple agents from a configuration map.
    ///
    /// @apiNote **Side effects**:
    /// - Modifies internal agent registry
    /// - Overwrites existing agents if IDs already registered
    ///
    /// @param configs map of agent ID to configuration, not null (may be empty)
    /// @throws NullPointerException if configs is null
    /// @throws IllegalStateException if any configured model is unsupported
    @Override
    public void registerAgents(Map<String, AgentConfig> configs) {
        configs.forEach(this::registerAgent);
    }

    /// Removes an agent from the registry.
    ///
    /// @apiNote **Side effects**:
    /// - Modifies internal agent registry
    /// - Logs removal at INFO level if agent existed
    ///
    /// @param agentId the agent identifier to remove, not null
    /// @return `true` if an agent was removed, `false` if no agent with this ID existed
    /// @throws NullPointerException if agentId is null
    public boolean unregisterAgent(String agentId) {
        Agent removed = agents.remove(agentId);
        if (removed != null) {
            logger.info("Unregistered agent: " + agentId);
            return true;
        }
        return false;
    }

    /// Checks whether an agent with the given ID is registered.
    ///
    /// @param agentId the agent identifier to check, not null
    /// @return `true` if an agent with this ID exists, `false` otherwise
    /// @throws NullPointerException if agentId is null
    @Override
    public boolean hasAgent(String agentId) {
        return agents.containsKey(agentId);
    }

    /// Returns the IDs of all registered agents.
    ///
    /// @return unmodifiable set of agent IDs, never null (may be empty)
    public Set<String> getAgentIds() {
        return Collections.unmodifiableSet(agents.keySet());
    }

    /// Removes all agents from the registry.
    ///
    /// @apiNote **Side effects**:
    /// - Clears internal agent registry
    /// - Logs count of removed agents at INFO level
    public void clear() {
        int count = agents.size();
        agents.clear();
        logger.info("Cleared " + count + " agents from registry");
    }

    /// Returns the number of registered agents.
    ///
    /// @return agent count, always non-negative
    public int size() {
        return agents.size();
    }

    /// Returns the underlying agent factory.
    ///
    /// Useful for checking available providers and supported models without
    /// creating agents.
    ///
    /// @return the agent factory, never null
    public AgentFactory getAgentFactory() {
        return agentFactory;
    }
}
