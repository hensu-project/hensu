package io.hensu.core.workflow.state;

/// Type system for workflow state variables.
///
/// Maps declared variable types to their runtime equivalents and JSON schema types.
/// Used by the engine to validate variable declarations at workflow load time and
/// to build JSON schemas for structured output when a node declares `writes`.
///
/// @see WorkflowStateSchema
/// @see StateVariableDeclaration
public enum VarType {

    /// Single-line or multi-line text. Maps to JSON {@code string}.
    STRING,

    /// Numeric value (integer or floating-point). Maps to JSON {@code number}.
    NUMBER,

    /// True/false flag. Maps to JSON {@code boolean}.
    BOOLEAN,

    /// Ordered list of text values. Maps to JSON {@code array} with {@code string} items.
    LIST_STRING
}
