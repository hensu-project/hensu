package io.hensu.cli.review;

/// What a reviewer answered about a tool call, or why nobody did.
///
/// The third constant is the one that matters. A run with no reviewer attached is not
/// an approval and is not an error either — it is a question that could not be asked,
/// and the only safe reading of it is a refusal. Collapsing it into
/// {@link #REJECTED} would report a human decision that never happened; collapsing it
/// into {@link #APPROVED} would make "nobody is watching" the condition under which
/// the most dangerous calls run.
///
/// @see ToolApprovalRequest for what the reviewer was shown
public enum ApprovalOutcome {

    /// A reviewer saw the resolved invocation and approved it. The call runs.
    APPROVED,

    /// A reviewer saw the resolved invocation and refused it. The call does not run,
    /// and the agent is told it was denied.
    REJECTED,

    /// No reviewer could be asked: the run is unattended, or its review channel is
    /// gone. Treated as a refusal, and recorded as a capability gap rather than as a
    /// human verdict.
    NO_REVIEWER
}
