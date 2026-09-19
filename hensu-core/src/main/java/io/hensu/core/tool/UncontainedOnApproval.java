package io.hensu.core.tool;

import java.util.Map;

/// Capability of a {@link ToolProvider} whose containment a reviewer may waive for
/// one call.
///
/// A host with no working sandbox backend leaves a command that requires containment
/// with two honest outcomes and no third: refuse it, or put the uncontained
/// invocation in front of a human and run it only if they say yes. Running it
/// quietly is the outcome the whole security model exists to prevent, and refusing
/// it outright turns every such host into one where the tool surface is empty.
///
/// The waiver is per call and is never the provider's own decision: the approval
/// decorator calls this method only after a reviewer approved the exact preview that
/// reported {@link ToolCallStatus#SANDBOX_UNAVAILABLE}. In a run with no reviewer
/// the call is refused instead, so this method is unreachable without a human.
///
/// Implementing it is optional. A provider that does not is simply never offered the
/// waiver, and its blocked calls stay blocked.
///
/// ### Contracts
/// - **Precondition**: a reviewer approved this exact call in this run
/// - **Postcondition**: the call runs without OS containment, and says so in the audit
///
/// @apiNote **Side effects**: runs the tool outside the sandbox. Every other guarantee
///     of the security model — argv-only binding, the catalog allowlist, parameter
///     validation, the audit trail — still holds; only {@link ToolPreview#sandboxSummary}
///     stops being enforced.
/// @see PreviewCapable for how the blocked state is reported
/// @see ToolCallStatus#SANDBOX_UNAVAILABLE for the outcome this waives
public interface UncontainedOnApproval {

    /// Invokes a tool that could not be contained, after a reviewer approved it.
    ///
    /// @param toolName the tool identifier to invoke, not null
    /// @param arguments arguments the agent supplied, not null (may be empty)
    /// @param context the live execution state context, not null
    /// @return the outcome of the call, never null
    ToolCallResult callUncontained(
            String toolName, Map<String, Object> arguments, Map<String, Object> context);
}
