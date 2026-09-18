package io.hensu.core.execution.action;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// The agent-visible face of a command, present only when a command opts in.
///
/// A command without a `tool:` block stays usable by workflow-authored
/// `execute(...)` actions and stays invisible to every agent. That opt-in is the
/// first gate of the execution security model: the agent-callable surface is
/// exactly the set of commands a human chose to expose, and it cannot be widened
/// by anything an agent says.
///
/// @param description what the tool does, shown to the model verbatim, not null
/// @param params the parameters the command accepts, not null (may be empty)
/// @implNote **Immutable after construction.** Safe to share across Virtual Threads.
/// @see ParamSpec for the per-parameter schema
/// @see CommandDefinition#agentVisible() for the opt-in check
public record ToolSpec(String description, List<ParamSpec> params) {

    /// Compact constructor with validation and a defensive copy.
    ///
    /// @throws NullPointerException if description is null
    public ToolSpec {
        Objects.requireNonNull(description, "description must not be null");
        params = params != null ? List.copyOf(params) : List.of();
    }

    /// Returns the schema for a named parameter.
    ///
    /// @param name the parameter identifier, not null
    /// @return the schema, or empty when the command declares no such parameter
    public Optional<ParamSpec> param(String name) {
        return params.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    /// Returns whether the command declares a parameter under this name.
    ///
    /// @param name the parameter identifier, not null
    /// @return true if the parameter is declared
    public boolean declares(String name) {
        return param(name).isPresent();
    }
}
