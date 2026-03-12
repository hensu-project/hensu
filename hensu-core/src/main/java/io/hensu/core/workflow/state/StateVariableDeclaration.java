package io.hensu.core.workflow.state;

import java.util.Objects;

/// Declares a single typed variable in the workflow state schema.
///
/// Variables declared as inputs are expected to be present in the initial execution context
/// supplied by the caller. Variables declared as outputs are produced by workflow nodes
/// via `writes`.
///
/// The optional `description` is surfaced in the engine-injected JSON output requirement so the
/// LLM knows exactly what content to produce for each field — removing any reliance on
/// semantic inference from the variable name alone.
///
/// @param name        unique variable identifier used in prompt templates as `{name}`, not null
///                    or blank
/// @param type        declared type used for JSON schema generation and validation, not null
/// @param isInput     `true` if supplied as workflow input; `false` if produced by a node
/// @param description human-readable hint for the LLM describing the expected content, or null
///
/// @see WorkflowStateSchema
public record StateVariableDeclaration(
        String name, VarType type, boolean isInput, String description) {

    public StateVariableDeclaration {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
    }

    /// Convenience constructor without description.
    ///
    /// @param name    variable identifier, not null or blank
    /// @param type    variable type, not null
    /// @param isInput `true` if this is a workflow input
    public StateVariableDeclaration(String name, VarType type, boolean isInput) {
        this(name, type, isInput, null);
    }
}
