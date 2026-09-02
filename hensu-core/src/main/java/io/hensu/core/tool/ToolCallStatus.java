package io.hensu.core.tool;

/// Outcome taxonomy shared by every tool invocation in the engine.
///
/// One vocabulary spans all layers so that a policy decision, a containment
/// failure and an ordinary tool error never collapse into the same "it did not
/// work" signal. The audit trail records the constant verbatim, the agent sees
/// it through {@link ToolCallResult#asText()}, and a reviewer can tell "the call
/// was refused" apart from "the tool ran and failed".
///
/// Some constants describe situations that only later layers can produce; they
/// are named here so every provider maps onto the same vocabulary from the
/// start rather than inventing its own error strings.
///
/// @see ToolCallResult for the result carrying this status
/// @see ToolResultEvent for the audit record carrying this status
public enum ToolCallStatus {

    /// The tool ran and reported success. Produced by any {@link ToolProvider}.
    SUCCESS,

    /// The tool ran and reported failure. Produced by any {@link ToolProvider}.
    FAILURE,

    /// No provider offers the requested name. Produced by {@link ToolRouter}
    /// when it cannot route, and by the tool loop when an agent hallucinates a
    /// tool outside its declared allowlist.
    UNKNOWN_TOOL,

    /// The catalog itself is unusable because two providers expose the same
    /// tool name. Produced by {@link ToolRouter}; the tool loop fails the node
    /// rather than feeding a configuration error back to the model.
    CATALOG_ERROR,

    /// Arguments were rejected before anything was launched. Produced by
    /// providers that validate against a declared parameter schema.
    VALIDATION_FAILED,

    /// A reviewer refused the call. Produced by the approval decorator in front
    /// of a provider; the tool never ran.
    DENIED,

    /// No working containment backend is available, so the call was not run.
    /// Produced by command execution on a host without a usable sandbox.
    SANDBOX_UNAVAILABLE,

    /// Containment failed at launch despite a passing probe. Produced by
    /// command execution when the sandbox refuses the prepared invocation.
    SANDBOX_REFUSED,

    /// The call exceeded its own deadline. Produced by providers that impose a
    /// wall clock on execution, as {@link ToolProvider#call} requires.
    TIMEOUT,

    /// The loop rejected the request before invoking it because the agent had
    /// spent its tool call budget. Produced by the tool loop.
    BUDGET_EXHAUSTED
}
