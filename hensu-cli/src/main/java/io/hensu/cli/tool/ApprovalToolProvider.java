package io.hensu.cli.tool;

import io.hensu.cli.review.ApprovalOutcome;
import io.hensu.cli.review.ToolApprovalRequest;
import io.hensu.core.tool.PreviewCapable;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolPreview;
import io.hensu.core.tool.ToolProvider;
import io.hensu.core.tool.ToolProviderDecorator;
import io.hensu.core.tool.UncontainedOnApproval;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/// A {@link ToolProvider} that applies the approval policy in front of another one.
///
/// The alternative was a copy of the same check inside every provider, which is one
/// policy written once per source and drifting from the day the second one is added.
/// What blocked the decorator originally is that an approval is only meaningful when the
/// reviewer sees the invocation that will run, and only the provider can resolve it;
/// {@link PreviewCapable} closes that gap, so the decision lives out here.
///
/// One consequence is worth stating plainly: a provider added to the deployment later is
/// gated by the producer wrapping it, with no edit to this class and none to the
/// provider. A provider that cannot describe a call is never run unattended.
///
/// The catalog is not filtered. {@link #tools()} passes through unchanged even for
/// entries this run will refuse, because an agent that cannot see a tool reports "I have
/// no way to do this" while an agent that calls a refused tool produces a capability gap
/// naming the entry an operator would have to grant. The second is the feedback loop;
/// the first is silence.
///
/// ### Contracts
/// - **Precondition**: the delegate offers whatever this is asked for (the router routes)
/// - **Postcondition**: a refused call has not run, and says so with
///   {@link ToolCallStatus#DENIED}
/// - **Invariant**: this never wraps itself — wrapping happens in the producer, and the
///   decorator is not a discovered bean
///
/// @see ToolApprovalGate for the matrix being applied
/// @see io.hensu.core.tool.UncontainedOnApproval for the one waiver a reviewer may grant
public final class ApprovalToolProvider implements ToolProviderDecorator {

    private static final Logger logger = Logger.getLogger(ApprovalToolProvider.class.getName());

    private static final String UNKNOWN_NODE = "unknown";

    private final ToolProvider delegate;
    private final ToolApprovalGate gate;

    /// Wraps one provider in the approval policy.
    ///
    /// @param delegate the provider whose calls are gated, not null
    /// @param gate the shared policy both paths consult, not null
    public ApprovalToolProvider(ToolProvider delegate, ToolApprovalGate gate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.gate = Objects.requireNonNull(gate, "gate must not be null");
    }

    /// Returns the provider being gated.
    ///
    /// @return the wrapped provider, never null
    @Override
    public ToolProvider delegate() {
        return delegate;
    }

    @Override
    public List<ToolDefinition> tools() {
        return delegate.tools();
    }

    @Override
    public List<ToolDefinition> settledTools() {
        return delegate.settledTools();
    }

    @Override
    public boolean provides(String toolName) {
        return delegate.provides(toolName);
    }

    /// Applies the policy, then either delegates the call or refuses it.
    ///
    /// @param toolName the tool identifier to invoke, not null
    /// @param arguments arguments the agent supplied, not null (may be empty)
    /// @param context the live execution state context, not null
    /// @return the outcome, never null
    /// @apiNote **Side effects**: may block until a reviewer answers. A refused call
    ///     launches nothing.
    @Override
    public ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context) {

        ToolPreview preview = preview(toolName, arguments);
        boolean unattended = gate.unattended(context);

        ToolCallResult result =
                switch (ToolApprovalGate.decide(unattended, preview)) {
                    case RUN -> delegate.call(toolName, arguments, context);
                    case REFUSE -> denied(toolName, refusal(toolName, preview));
                    case ASK -> reviewed(toolName, arguments, context, preview);
                };

        // The audit trail has to record the invocation a reviewer was shown, and this is
        // the only layer that holds it — the provider resolved it, the loop never sees it.
        return preview != null && !preview.argv().isEmpty()
                ? result.withArgv(preview.argv())
                : result;
    }

    // ------------------------------------------------------------------ approval

    private ToolCallResult reviewed(
            String toolName,
            Map<String, Object> arguments,
            Map<String, Object> context,
            ToolPreview preview) {

        ToolApprovalRequest request = request(toolName, context, preview);
        ApprovalOutcome outcome = gate.ask(request);

        return switch (outcome) {
            case APPROVED -> approved(toolName, arguments, context, preview);
            case REJECTED -> denied(toolName, "A reviewer refused this call; it did not run.");
            case NO_REVIEWER ->
                    denied(
                            toolName,
                            "'"
                                    + toolName
                                    + "' needs a reviewer and none answered; nothing was"
                                    + " launched.");
        };
    }

    private ToolCallResult approved(
            String toolName,
            Map<String, Object> arguments,
            Map<String, Object> context,
            ToolPreview preview) {

        boolean waived = preview != null && preview.blocked() == ToolCallStatus.SANDBOX_UNAVAILABLE;
        if (waived && delegate instanceof UncontainedOnApproval uncontained) {
            logger.warning(
                    "Running '"
                            + toolName
                            + "' without containment: a reviewer approved the uncontained"
                            + " invocation.");
            return uncontained.callUncontained(toolName, arguments, context);
        }
        return delegate.call(toolName, arguments, context);
    }

    // ------------------------------------------------------------------ description

    private ToolPreview preview(String toolName, Map<String, Object> arguments) {
        if (!(delegate instanceof PreviewCapable previewCapable)) {
            return null;
        }
        try {
            return previewCapable.preview(toolName, arguments);
        } catch (RuntimeException e) {
            // A provider that cannot describe the call has told us something: it is not
            // safe to run it unattended. Failing the whole node instead would let one
            // broken provider abort an execution, which finding 3 already ruled out.
            logger.warning(
                    "Could not describe '" + toolName + "' before calling it: " + e.getMessage());
            return null;
        }
    }

    private ToolApprovalRequest request(
            String toolName, Map<String, Object> context, ToolPreview preview) {
        return new ToolApprovalRequest(
                text(context, "_execution_id", UNKNOWN_NODE),
                text(context, "current_node", UNKNOWN_NODE),
                toolName,
                preview != null ? preview.summary() : toolName,
                preview != null ? preview.argv() : List.of(),
                preview != null ? preview.sandboxSummary() : "",
                reason(toolName, preview));
    }

    /// Why a human is being asked. One sentence per gate, so the reviewer and the agent
    /// never see "requires approval" where the truth was "the sandbox is gone".
    private static String reason(String toolName, ToolPreview preview) {
        if (preview == null) {
            return "'" + toolName + "' cannot describe what it would do.";
        }
        if (preview.blocked() == ToolCallStatus.SANDBOX_UNAVAILABLE) {
            return "no working sandbox backend is available, so approving runs '"
                    + toolName
                    + "' without OS containment.";
        }
        return "'" + toolName + "' is declared approval: required.";
    }

    /// Why a call was refused outright. Distinct per gate for the same reason: an agent
    /// that reads "not granted to this node" goes looking for another tool, while one
    /// that reads "this run is unattended" knows to stop and report.
    private static String refusal(String toolName, ToolPreview preview) {
        if (preview == null) {
            return "'"
                    + toolName
                    + "' cannot describe what it would do, and this run has no human to"
                    + " approve it; nothing was launched.";
        }
        if (preview.blocked() == ToolCallStatus.SANDBOX_UNAVAILABLE) {
            return "'"
                    + toolName
                    + "' needs OS containment, no working sandbox backend is available, and"
                    + " this run has no human to approve running it uncontained; nothing was"
                    + " launched.";
        }
        if (preview.approvalRequired()) {
            return "'"
                    + toolName
                    + "' is declared approval: required and this run is unattended; nothing"
                    + " was launched.";
        }
        return "'"
                + toolName
                + "' is declared unattended: false and this run has no human present;"
                + " nothing was launched.";
    }

    private static ToolCallResult denied(String toolName, String message) {
        return ToolCallResult.of(toolName, ToolCallStatus.DENIED, null, message, null);
    }

    private static String text(Map<String, Object> context, String key, String fallback) {
        Object value = context != null ? context.get(key) : null;
        return value != null ? String.valueOf(value) : fallback;
    }
}
