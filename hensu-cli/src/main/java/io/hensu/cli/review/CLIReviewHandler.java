package io.hensu.cli.review;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/// CLI-based human review handler for interactive workflow checkpoints.
///
/// Thin adapter that converts core domain objects into {@link ReviewData} and
/// delegates all terminal rendering to {@link ReviewTerminal}.
///
/// ### Enabling Interactive Mode
/// Set the system property {@code hensu.review.interactive=true} to enable prompts.
/// When disabled (default), all reviews are automatically approved.
///
/// @implNote **Not thread-safe.** Designed for single-threaded CLI usage.
///
/// @see ReviewTerminal
/// @see ReviewDecision
public class CLIReviewHandler implements ReviewHandler {

    /// System property key to enable/disable interactive mode at runtime.
    public static final String INTERACTIVE_PROPERTY = "hensu.review.interactive";

    private final ReviewTerminal terminal;

    public CLIReviewHandler() {
        this(new Scanner(System.in), System.out, true);
    }

    public CLIReviewHandler(Scanner scanner, PrintStream out, boolean useColor) {
        this.terminal = new ReviewTerminal(scanner, out, useColor);
    }

    @Override
    public ReviewDecision requestReview(
            Node node,
            NodeResult result,
            HensuState state,
            ExecutionHistory history,
            ReviewConfig config,
            Workflow workflow) {
        if (!isInteractive()) {
            return new ReviewDecision.Approve(null);
        }
        return terminal.runReview(toReviewData(node, result, state, history, config, workflow));
    }

    // — Private ———————————————————————————————————————————————————————————————

    private boolean isInteractive() {
        return Boolean.parseBoolean(System.getProperty(INTERACTIVE_PROPERTY, "false"));
    }

    private ReviewData toReviewData(
            Node node,
            NodeResult result,
            HensuState state,
            ExecutionHistory history,
            ReviewConfig config,
            Workflow workflow) {
        RubricEvaluation rubric = state.getRubricEvaluation();
        String output = result.getOutput() != null ? result.getOutput().toString() : null;

        List<ReviewData.StepInfo> steps =
                history.getSteps().stream()
                        .map(
                                s ->
                                        new ReviewData.StepInfo(
                                                s.getNodeId(),
                                                s.getResult().getStatus().name(),
                                                resolvePrompt(workflow, s.getNodeId())))
                        .toList();

        Map<String, Object> context = state.getContext() != null ? state.getContext() : Map.of();

        return new ReviewData(
                node.getId(),
                result.getStatus().name(),
                output,
                rubric != null ? rubric.getScore() : null,
                rubric != null ? rubric.isPassed() : null,
                config.isAllowBacktrack(),
                steps,
                resolvePrompt(workflow, node.getId()),
                context);
    }

    private static String resolvePrompt(Workflow workflow, String nodeId) {
        return ReviewUtils.resolvePrompt(workflow, nodeId);
    }
}
