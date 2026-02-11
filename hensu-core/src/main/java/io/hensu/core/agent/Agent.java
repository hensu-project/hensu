package io.hensu.core.agent;

import java.util.Map;

/// Core interface for AI agents that execute tasks within workflows.
///
/// Agents are the primary execution units in Hensu workflows. Each agent wraps
/// an underlying AI model (e.g., Claude, GPT) and provides a unified interface
/// for prompt execution with context variables.
///
/// ### Contracts
/// - **Precondition**: Agent must be fully configured before `execute()` is called
/// - **Postcondition**: `execute()` always returns a non-null {@link AgentResponse}
/// - **Invariant**: Agent ID and config remain constant after construction
///
/// @implNote Implementations should be thread-safe. Multiple workflow nodes may
/// invoke the same agent concurrently during parallel execution.
///
/// @see AgentRegistry for agent lifecycle management
/// @see AgentFactory for agent instantiation
/// @see AgentProvider for implementing custom agent backends
public interface Agent {

    /// Executes an agent task with the given prompt and execution context.
    ///
    /// The prompt may contain template variables in `{variable}` syntax that
    /// are resolved from the context map before execution.
    ///
    /// @param prompt the instruction/prompt for the agent, not null
    /// @param context execution context with variables for template resolution, not null
    /// @return execution result containing output or error details, never null
    /// @throws NullPointerException if prompt or context is null
    AgentResponse execute(String prompt, Map<String, Object> context);

    /// Returns the unique identifier used for agent registry lookup.
    ///
    /// @return non-null identifier, stable for the agent's lifetime
    String getId();

    /// Returns the configuration used to create this agent.
    ///
    /// @return immutable agent configuration, never null
    AgentConfig getConfig();
}
