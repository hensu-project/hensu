package io.hensu.cli.tool;

import io.hensu.cli.review.ApprovalOutcome;
import io.hensu.cli.review.DaemonReviewHandler;
import io.hensu.cli.review.ToolApprovalRequest;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolPreview;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// The one place that decides whether a tool call may run, and who is asked.
///
/// Two sources of policy meet here and nowhere else. The **run** contributes whether a
/// human is present; the **entry** contributes `unattended:` and `approval:` from
/// `commands.yaml`, or the equivalent flags on an `mcp.yaml` server. Writing the
/// combination twice — once per provider — is how the two paths drift, so both consult
/// this class and neither implements the rule.
///
/// ### The matrix
///
/// | Run mode   | Entry declares                         | Verdict            |
/// |------------|----------------------------------------|--------------------|
/// | attended   | `approval: none`                       | {@link Verdict#RUN} |
/// | attended   | `approval: required`                   | {@link Verdict#ASK} |
/// | unattended | `unattended: true`, `approval: none`   | {@link Verdict#RUN} |
/// | unattended | `unattended: false`                    | {@link Verdict#REFUSE} |
/// | unattended | `approval: required`                   | {@link Verdict#REFUSE} |
///
/// An absent sandbox backend enters the same table: the call is demoted to
/// {@link Verdict#ASK}, and refused where there is nobody to ask. `unattended: false` is
/// never "ask anyway" — in a run with no human there is nobody to ask, so the call is
/// refused with a typed outcome the workflow can route on.
///
/// ### Where the run mode comes from
///
/// The engine has no notion of human presence and does not gain one. The CLI decides it
/// from `--interactive` / `--unattended` and writes one boolean into the execution
/// context under {@link #RUN_MODE_KEY}, which reaches a provider as part of the ordinary
/// call context. A context with no such key is unattended: a deployment that never
/// installed a reviewer has none.
///
/// @see ApprovalToolProvider for the decorator that applies these verdicts
/// @see io.hensu.core.tool.CapabilityGaps for what a refusal becomes on the state
@Singleton
public class ToolApprovalGate {

    /// Context key carrying the run's own mode, written by the CLI at run start.
    ///
    /// `Boolean.TRUE` means no human is present. Absent means the same: a run that never
    /// declared itself attended is treated as unattended.
    public static final String RUN_MODE_KEY = "_run_unattended";

    /// What the gate decided about one call.
    public enum Verdict {

        /// Policy is satisfied. The call proceeds to the provider unchanged.
        RUN,

        /// A human must see the resolved invocation before it runs.
        ASK,

        /// The call does not run and no human is asked, because there is none.
        REFUSE
    }

    private final DaemonReviewHandler reviewHandler;

    /// Run mode of every execution currently in flight, keyed by execution id.
    ///
    /// A daemon serves several runs at once, so a single field would answer for whichever
    /// run wrote it last — an unattended run could then be handed the verdict an attended
    /// one established. Entries are added by {@link #setRunMode} at run start and removed
    /// by {@link #endRun} when the run finishes, so the map is bounded by concurrency
    /// rather than by uptime.
    private final Map<String, Boolean> unattendedByExecution = new ConcurrentHashMap<>();

    @Inject
    public ToolApprovalGate(DaemonReviewHandler reviewHandler) {
        this.reviewHandler = Objects.requireNonNull(reviewHandler, "reviewHandler required");
    }

    /// Decides one call from the run's mode and the entry's own declarations.
    ///
    /// A null preview means the provider could not describe the call. That is treated as
    /// never safe unattended, because nothing can say what it would do — the same rule
    /// {@link io.hensu.core.tool.PreviewCapable} states for providers that do not
    /// implement it.
    ///
    /// @param unattended true when no human is present for this run
    /// @param preview what the call would do, may be null
    /// @return the verdict, never null
    public static Verdict decide(boolean unattended, ToolPreview preview) {
        boolean unattendedSafe = preview != null && preview.unattendedSafe();
        boolean approvalRequired = preview != null && preview.approvalRequired();
        boolean containmentMissing =
                preview != null && preview.blocked() == ToolCallStatus.SANDBOX_UNAVAILABLE;

        if (containmentMissing || approvalRequired) {
            return unattended ? Verdict.REFUSE : Verdict.ASK;
        }
        return unattended && !unattendedSafe ? Verdict.REFUSE : Verdict.RUN;
    }

    /// Reads the run's mode out of an execution context.
    ///
    /// @param context the live state context, may be null
    /// @return true when no human is present, including when the key is absent
    public boolean unattended(Map<String, Object> context) {
        if (context == null) {
            return true;
        }
        return !Boolean.FALSE.equals(context.get(RUN_MODE_KEY));
    }

    /// Registers a run's mode for the decisions that are not attached to a call.
    ///
    /// @param executionId id of the run starting, not null
    /// @param unattended true when that run has no human present
    /// @see #endRun for the matching removal, which every caller owes
    public void setRunMode(String executionId, boolean unattended) {
        unattendedByExecution.put(
                Objects.requireNonNull(executionId, "executionId required"), unattended);
    }

    /// Forgets a finished run's mode.
    ///
    /// @param executionId id of the run that ended, may be null
    public void endRun(String executionId) {
        if (executionId != null) {
            unattendedByExecution.remove(executionId);
        }
    }

    /// Returns the run whose reviewer may answer a decision that belongs to no call.
    ///
    /// Launching a long-lived MCP server is the only such decision: it happens once, on
    /// the first tool resolution, before any call context exists, and the process it
    /// starts outlives every call it answers. Asking a human for it therefore needs a
    /// human who is answering for the whole process, not for one call.
    ///
    /// The answer is empty — meaning refuse rather than escalate — unless exactly one run
    /// is in flight and that run is attended. Two runs cannot be distinguished here, and
    /// one unattended run in the set means the launch would outlive a run that never had
    /// a reviewer to consent to it.
    ///
    /// @return id of the sole attended run, or empty when there is nobody to ask
    public Optional<String> attendedReviewer() {
        String sole = null;
        for (Map.Entry<String, Boolean> run : unattendedByExecution.entrySet()) {
            if (Boolean.TRUE.equals(run.getValue()) || sole != null) {
                return Optional.empty();
            }
            sole = run.getKey();
        }
        return Optional.ofNullable(sole);
    }

    /// Puts one call in front of a human and returns what they said.
    ///
    /// @param request the call needing a decision, not null
    /// @return the reviewer's answer, or {@link ApprovalOutcome#NO_REVIEWER} when there
    ///     was nobody to ask, never null
    /// @apiNote **Side effects**: blocks the calling tool loop until the reviewer answers.
    public ApprovalOutcome ask(ToolApprovalRequest request) {
        return reviewHandler.requestToolApproval(request);
    }
}
