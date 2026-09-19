package io.hensu.cli.review;

import java.util.List;
import java.util.Objects;

/// What a reviewer is shown when a tool call needs a human decision.
///
/// The request carries the invocation as it would run, not the template it came from.
/// A reviewer approving `git push --force origin main` has approved those five tokens;
/// approving `git {args}` would be approving a shape and trusting the binding, which is
/// the trust the whole argv-only execution model exists to avoid.
///
/// Sources differ in what "the invocation" means and the record admits that rather than
/// forcing one shape: a catalog command has an argv, a tool on a long-lived MCP server
/// has a server name and an argument summary, and both have a sandbox summary and a
/// reason the approval is being asked for at all.
///
/// ### Contracts
/// - **Invariant**: no argument **value** declared `secret:` appears in any field
/// - **Postcondition**: the fields describe the call the run would make next, unchanged
///
/// @param executionId id of the execution making the call, not null
/// @param nodeId id of the node whose agent asked, not null
/// @param toolName the tool the agent asked for, not null
/// @param summary one line naming what would happen, not null
/// @param argv the resolved argv, not null (empty for tools that run no process)
/// @param sandboxSummary the containment that would apply, not null (may be empty)
/// @param reason why this call needs a decision, not null
/// @see ApprovalOutcome for what a reviewer answers
/// @see DaemonReviewHandler#requestToolApproval for the routing seam
public record ToolApprovalRequest(
        String executionId,
        String nodeId,
        String toolName,
        String summary,
        List<String> argv,
        String sandboxSummary,
        String reason) {

    /// Compact constructor taking a defensive copy of the argv and rejecting missing text.
    public ToolApprovalRequest {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(sandboxSummary, "sandboxSummary must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        argv = argv != null ? List.copyOf(argv) : List.of();
    }
}
