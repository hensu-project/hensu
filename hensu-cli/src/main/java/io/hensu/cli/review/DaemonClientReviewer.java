package io.hensu.cli.review;

import io.hensu.cli.daemon.DaemonFrame;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.workflow.Workflow;
import io.hensu.serialization.WorkflowSerializer;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/// Client-side review handler for daemon mode.
///
/// Converts a {@link DaemonFrame.ReviewPayload} received over the Unix socket into
/// {@link ReviewData} and delegates terminal rendering to {@link ReviewTerminal}.
/// Used by the CLI client process (which owns the real TTY) when the daemon sends a
/// {@code review_request} frame.
///
/// @see DaemonReviewHandler
/// @see ReviewTerminal
public class DaemonClientReviewer {

    private final ReviewTerminal terminal;

    public DaemonClientReviewer() {
        this(new Scanner(System.in), System.out, true);
    }

    public DaemonClientReviewer(Scanner scanner, PrintStream out, boolean useColor) {
        this.terminal = new ReviewTerminal(scanner, out, useColor);
    }

    /// Presents the interactive review UI from a daemon payload and returns the decision.
    ///
    /// @param payload structured review data from the daemon, not null
    /// @return the reviewer's decision, never null
    public ReviewDecision review(DaemonFrame.ReviewPayload payload) {
        return terminal.runReview(toReviewData(payload));
    }

    // — Private ———————————————————————————————————————————————————————————————

    private ReviewData toReviewData(DaemonFrame.ReviewPayload payload) {
        List<ReviewData.StepInfo> steps = List.of();
        if (payload.historySteps() != null) {
            steps =
                    payload.historySteps().stream()
                            .map(
                                    s ->
                                            new ReviewData.StepInfo(
                                                    s.nodeId(), s.status(), s.promptTemplate()))
                            .toList();
        }

        Workflow workflow = deserializeWorkflow(payload.workflowJson());
        Map<String, Object> context = payload.context() != null ? payload.context() : Map.of();
        String promptTemplate = workflow != null ? resolvePrompt(workflow, payload.nodeId()) : null;

        return new ReviewData(
                payload.nodeId(),
                payload.status(),
                payload.output(),
                payload.rubricScore(),
                payload.rubricPassed(),
                payload.allowBacktrack(),
                steps,
                promptTemplate,
                context);
    }

    private static Workflow deserializeWorkflow(String workflowJson) {
        if (workflowJson == null || workflowJson.isBlank()) return null;
        try {
            return WorkflowSerializer.fromJson(workflowJson);
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolvePrompt(Workflow workflow, String nodeId) {
        return ReviewUtils.resolvePrompt(workflow, nodeId);
    }
}
