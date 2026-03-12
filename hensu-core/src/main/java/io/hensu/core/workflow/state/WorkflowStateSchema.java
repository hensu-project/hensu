package io.hensu.core.workflow.state;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Typed schema for custom state variables declared at the workflow level.
///
/// An optional declaration on a {@link io.hensu.core.workflow.Workflow} that lists all
/// domain-specific state variables, their types, and whether they are inputs or outputs.
/// Absent a schema, the engine operates in legacy mode (outputs keyed by node ID).
///
/// ### Engine variables
/// The following variables are always implicitly valid — no declaration required and must
/// not appear in `writes()` declarations:
/// - `score`          — rubric or self-evaluation score (0-100), drives `onScore` transitions
/// - `approved`       — boolean approval flag, drives `onApproval` / `onRejection` transitions
/// - `recommendation` — improvement feedback string, auto-injected on scoring/approval nodes
///
/// ### Validation
/// {@link io.hensu.core.workflow.validation.WorkflowValidator} uses this schema to verify
/// that every `writes` declaration and every `{variable}` reference in prompts refers to
/// a declared variable or an engine variable.
///
/// @implNote **Immutable after construction.**
///
/// @see StateVariableDeclaration for individual variable metadata
/// @see VarType for supported types
public final class WorkflowStateSchema {

    /// Engine-semantic variables always implicitly declared.
    /// Nodes may write to these and prompts may reference them without a schema entry.
    /// These must never be declared in the workflow state schema or in node {@code writes()}.
    ///
    /// - `score`          — numeric quality score (0-100), driven by rubric evaluation or
    ///                      self-scoring; drives `onScore` transitions
    /// - `approved`       — boolean approval flag written by classification/review nodes;
    ///                      drives `onApproval` / `onRejection` transitions
    /// - `recommendation` — plain-string improvement feedback or review reasoning, injected
    ///                      automatically on any node with `onScore` or `onApproval` routing
    public static final Set<String> ENGINE_VARIABLES =
            Set.of("score", "approved", "recommendation");

    private final List<StateVariableDeclaration> variables;
    private final Map<String, StateVariableDeclaration> index;

    /// Constructs a schema from a list of variable declarations.
    ///
    /// @param variables ordered list of variable declarations, not null
    /// @throws IllegalArgumentException if any variable name is duplicated
    public WorkflowStateSchema(List<StateVariableDeclaration> variables) {
        Objects.requireNonNull(variables, "variables must not be null");
        Map<String, StateVariableDeclaration> idx = new LinkedHashMap<>();
        for (StateVariableDeclaration v : variables) {
            if (idx.put(v.name(), v) != null) {
                throw new IllegalArgumentException("Duplicate state variable: '" + v.name() + "'");
            }
        }
        this.variables = List.copyOf(variables);
        this.index = Collections.unmodifiableMap(idx);
    }

    /// Returns true if the name is declared in this schema or is an engine variable.
    ///
    /// @param name variable name to check, not null
    /// @return true if the name is known to the engine
    public boolean contains(String name) {
        return ENGINE_VARIABLES.contains(name) || index.containsKey(name);
    }

    /// Returns the declared type for a custom variable.
    ///
    /// Engine variables (`score`, `approved`) are not present in the index — callers
    /// should treat `score` as {@link VarType#NUMBER} and `approved` as
    /// {@link VarType#BOOLEAN} when needed.
    ///
    /// @param name variable name, not null
    /// @return declared type, or empty if not declared (or is an engine variable)
    public Optional<VarType> typeOf(String name) {
        return Optional.ofNullable(index.get(name)).map(StateVariableDeclaration::type);
    }

    /// Returns the description for a custom variable, if declared.
    ///
    /// Used by {@link io.hensu.core.execution.enricher.WritesVariableInjector} to generate
    /// rich LLM output requirements that name each field and its expected content.
    ///
    /// @param name variable name, not null
    /// @return description string, or empty if not declared or no description was provided
    public Optional<String> descriptionOf(String name) {
        return Optional.ofNullable(index.get(name)).map(StateVariableDeclaration::description);
    }

    /// Returns all declared variables in declaration order.
    ///
    /// @return immutable list, never null
    public List<StateVariableDeclaration> getVariables() {
        return variables;
    }
}
