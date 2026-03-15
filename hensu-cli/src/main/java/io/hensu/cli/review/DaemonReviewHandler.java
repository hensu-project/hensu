package io.hensu.cli.review;

import io.hensu.cli.daemon.DaemonFrame;
import io.hensu.cli.daemon.ExecutionStatus;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.serialization.WorkflowSerializer;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/// Application-scoped review handler that routes between daemon-mode and inline CLI review.
///
/// ### Routing
/// The routing key is the execution ID from {@link HensuState#getExecutionId()}:
///
/// - **Daemon mode** — when the execution ID is registered via {@link #registerExecution},
///   this handler sends a {@code review_request} frame to the connected client over the
///   Unix socket and blocks the execution virtual thread until a {@code review_response}
///   arrives. Blocking a virtual thread is cheap under Project Loom — no carrier thread
///   is pinned.
/// - **Inline mode** — when no registration exists, delegates to {@link CLIReviewHandler},
///   which reads interactively from {@code System.in}.
///
/// ### Disconnect / Suspend / Resume
/// When a client disconnects (Ctrl+C / socket close), {@link #suspendExecution} removes
/// the frame sender but leaves pending review entries alive. The execution virtual thread
/// continues to block on its future until a client reattaches. On reattach,
/// {@link #resumeExecution} registers a new sender and re-delivers any pending
/// {@code review_request} frames so the reconnecting client can present the review UI
/// immediately with the same {@code review_id} for correlation.
///
/// If no client reattaches within {@value #REVIEW_TIMEOUT_MINUTES} minutes, the future
/// times out and the review auto-approves — an acceptable last-resort fallback.
///
/// ### Lifecycle
/// {@code DaemonServer} must call {@link #registerExecution} before starting the workflow
/// executor and {@link #unregisterExecution} in a {@code finally} block after completion.
///
/// @see io.hensu.cli.daemon.DaemonServer
/// @see CLIReviewHandler
/// @see DaemonFrame#reviewRequest
@ApplicationScoped
public class DaemonReviewHandler implements ReviewHandler {

    private static final int REVIEW_TIMEOUT_MINUTES = 30;

    /// Bundles the per-connection frame sender and the per-execution status notifier.
    ///
    /// {@code frameSender} is {@code null} when the execution is suspended (no client
    /// attached). {@code statusNotifier} lives for the full execution lifetime and is
    /// retained across suspend/resume cycles.
    private record ExecContext(
            Consumer<DaemonFrame> frameSender, Consumer<ExecutionStatus> statusNotifier) {

        ExecContext suspend() {
            return new ExecContext(null, statusNotifier);
        }

        ExecContext resume(Consumer<DaemonFrame> newSender) {
            return new ExecContext(newSender, statusNotifier);
        }
    }

    /// Bundles the blocking future and the wire frame for a single review checkpoint.
    /// Stored as a single map entry to guarantee the two are always removed together.
    private record PendingReview(CompletableFuture<ReviewDecision> future, DaemonFrame frame) {}

    private final ConcurrentHashMap<String, ExecContext> execContexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingReview> pendingReviews =
            new ConcurrentHashMap<>();
    /// Tracks which reviewIds belong to each execution for disconnect cleanup.
    private final ConcurrentHashMap<String, Set<String>> execReviewIds = new ConcurrentHashMap<>();
    private final CLIReviewHandler cliDelegate = new CLIReviewHandler();

    /// Registers a daemon execution so review checkpoints route through the socket.
    ///
    /// Must be called before the workflow executor starts for this execution.
    ///
    /// @param execId          execution identifier, not null
    /// @param frameSender     consumer that enqueues serialized frames for the client, not null
    /// @param statusNotifier  notified with {@link ExecutionStatus#AWAITING_REVIEW} when a
    ///                        review blocks the execution, and with {@link ExecutionStatus#RUNNING}
    ///                        when the review completes; not null
    public void registerExecution(
            String execId,
            Consumer<DaemonFrame> frameSender,
            Consumer<ExecutionStatus> statusNotifier) {
        execContexts.put(execId, new ExecContext(frameSender, statusNotifier));
        execReviewIds.put(execId, ConcurrentHashMap.newKeySet());
    }

    /// Unregisters a daemon execution after it completes or fails.
    ///
    /// Also cancels any lingering pending reviews (defensive — normally none remain
    /// after execution completes).
    ///
    /// @param execId execution identifier to unregister, not null
    public void unregisterExecution(String execId) {
        execContexts.remove(execId);
        cancelPendingReviews(execId);
        execReviewIds.remove(execId);
    }

    /// Suspends the frame sender for an execution without cancelling pending review futures.
    ///
    /// Called when the client disconnects (Ctrl+C / socket close) but the execution should
    /// remain paused at the review checkpoint rather than auto-approving. The pending
    /// {@link PendingReview} entry remains alive; the execution virtual thread continues to
    /// block until either a client reattaches (see {@link #resumeExecution}) or the
    /// {@value #REVIEW_TIMEOUT_MINUTES}-minute timeout fires.
    ///
    /// @param execId execution identifier, not null
    public void suspendExecution(String execId) {
        execContexts.computeIfPresent(execId, (_, ctx) -> ctx.suspend());
    }

    /// Re-registers a frame sender for a suspended interactive execution and re-delivers
    /// any pending {@code review_request} frames to the new client.
    ///
    /// Called by {@code DaemonServer} when a client attaches to an execution that was
    /// previously suspended via {@link #suspendExecution}. The reconnecting client
    /// immediately receives the review UI for all checkpoints still waiting.
    ///
    /// No-op if the execution is not registered (i.e., already completed).
    ///
    /// @param execId      execution identifier, not null
    /// @param frameSender new consumer to deliver frames to the reconnecting client, not null
    public void resumeExecution(String execId, Consumer<DaemonFrame> frameSender) {
        ExecContext ctx = execContexts.computeIfPresent(execId, (_, c) -> c.resume(frameSender));
        if (ctx == null) return;
        Set<String> reviewIds = execReviewIds.get(execId);
        if (reviewIds == null) return;
        for (String reviewId : reviewIds) {
            PendingReview pending = pendingReviews.get(reviewId);
            if (pending != null) {
                frameSender.accept(pending.frame());
            }
        }
    }

    /// Returns {@code true} if this execution was registered for interactive (daemon) review.
    ///
    /// Remains {@code true} from {@link #registerExecution} until {@link #unregisterExecution}
    /// is called, regardless of whether a client is currently connected. Use this to
    /// distinguish a suspended-but-interactive execution from a non-interactive one.
    ///
    /// @param execId execution identifier, not null
    /// @return {@code true} if the execution is registered for interactive review
    public boolean isInteractive(String execId) {
        return execContexts.containsKey(execId);
    }

    /// Cancels all pending reviews for a given execution by completing their futures
    /// with a default {@link ReviewDecision.Approve}.
    ///
    /// Called by {@link #unregisterExecution} when an execution finishes, as a defensive
    /// cleanup for any reviews that somehow remain unresolved at completion time.
    ///
    /// @param execId execution identifier whose reviews should be canceled, not null
    public void cancelPendingReviews(String execId) {
        Set<String> reviewIds = execReviewIds.get(execId);
        if (reviewIds == null) return;
        for (String reviewId : reviewIds) {
            PendingReview pending = pendingReviews.remove(reviewId);
            if (pending != null) {
                pending.future().complete(new ReviewDecision.Approve(null));
            }
        }
        reviewIds.clear();
    }

    /// Completes a pending review initiated by {@link #requestReview}.
    ///
    /// Called by {@code DaemonServer} when a {@code review_response} frame arrives from
    /// the client. Unblocks the execution virtual thread waiting in {@link #requestReview}.
    ///
    /// @param reviewId unique correlation ID from the {@code review_request} frame
    /// @param decision  the reviewer's decision, not null
    public void completeReview(String reviewId, ReviewDecision decision) {
        PendingReview pending = pendingReviews.remove(reviewId);
        if (pending != null) {
            pending.future().complete(decision);
        }
    }

    @Override
    public ReviewDecision requestReview(
            Node node,
            NodeResult result,
            HensuState state,
            ExecutionHistory history,
            ReviewConfig config,
            Workflow workflow) {
        // Only delegate to inline CLI if this execution is not registered for daemon review.
        // An execution may be registered but temporarily suspended (no sender) — in that case
        // we still block and wait for a client to reattach, not fall through to stdin.
        if (!isInteractive(state.getExecutionId())) {
            return cliDelegate.requestReview(node, result, state, history, config, workflow);
        }
        return sendReviewRequest(
                state.getExecutionId(), node, result, state, history, config, workflow);
    }

    // — Private ———————————————————————————————————————————————————————————————

    private ReviewDecision sendReviewRequest(
            String execId,
            Node node,
            NodeResult result,
            HensuState state,
            ExecutionHistory history,
            ReviewConfig config,
            Workflow workflow) {
        String reviewId = UUID.randomUUID().toString();
        CompletableFuture<ReviewDecision> future = new CompletableFuture<>();

        DaemonFrame.ReviewPayload payload =
                buildPayload(node, result, state, history, config, workflow);
        DaemonFrame frame = DaemonFrame.reviewRequest(execId, reviewId, payload);

        // Store future + frame together before sending so:
        // (a) completeReview can unblock the future on response, and
        // (b) resumeExecution can re-deliver the frame if the client disconnects.
        pendingReviews.put(reviewId, new PendingReview(future, frame));

        Set<String> ids = execReviewIds.get(execId);
        if (ids != null) ids.add(reviewId);

        ExecContext ctx = execContexts.get(execId);
        if (ctx != null) {
            // Notify before blocking so hensu ps shows AWAITING_REVIEW immediately.
            ctx.statusNotifier().accept(ExecutionStatus.AWAITING_REVIEW);
            // Send to the current client if one is connected; if suspended (no sender), the
            // frame sits in pendingReviews until resumeExecution delivers it on reattach.
            if (ctx.frameSender() != null) {
                ctx.frameSender().accept(frame);
            }
        }

        try {
            return future.get(REVIEW_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return new ReviewDecision.Approve(null);
        } finally {
            pendingReviews.remove(reviewId);
            if (ids != null) ids.remove(reviewId);
            // Restore RUNNING so hensu ps reflects that execution has resumed.
            ExecContext current = execContexts.get(execId);
            if (current != null) {
                current.statusNotifier().accept(ExecutionStatus.RUNNING);
            }
        }
    }

    private DaemonFrame.ReviewPayload buildPayload(
            Node node,
            NodeResult result,
            HensuState state,
            ExecutionHistory history,
            ReviewConfig config,
            Workflow workflow) {
        RubricEvaluation rubric = state.getRubricEvaluation();
        String output = result.getOutput() != null ? result.getOutput().toString() : null;
        List<DaemonFrame.HistoryStep> steps =
                history.getSteps().stream()
                        .map(
                                s ->
                                        new DaemonFrame.HistoryStep(
                                                s.getNodeId(),
                                                s.getResult().getStatus().name(),
                                                resolvePrompt(workflow, s.getNodeId())))
                        .toList();

        String workflowJson = null;
        if (config.isAllowBacktrack()) {
            try {
                workflowJson = WorkflowSerializer.toJson(workflow);
            } catch (Exception ignored) {
            }
        }

        return new DaemonFrame.ReviewPayload(
                node.getId(),
                output,
                result.getStatus().name(),
                rubric != null ? rubric.getScore() : null,
                rubric != null ? rubric.isPassed() : null,
                config.isAllowBacktrack(),
                steps,
                workflowJson,
                state.getContext());
    }

    private static String resolvePrompt(Workflow workflow, String nodeId) {
        return ReviewUtils.resolvePrompt(workflow, nodeId);
    }
}
