package io.hensu.cli.review;

import io.hensu.cli.ui.AnsiStyles;
import io.hensu.core.review.ReviewDecision;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/// Terminal UI for human-review checkpoints.
///
/// Contains all display rendering, input capture, and interactive menu logic.
/// Works entirely from primitive types — no dependency on domain objects or
/// wire protocol frames. Callers (adapters) are responsible for converting
/// their data sources into the primitives this class expects.
///
/// ### Thread Safety
/// **Not thread-safe.** Designed for single-threaded use per Scanner/PrintStream pair.
///
/// @see CLIReviewHandler
/// @see DaemonClientReviewer
class ReviewTerminal {

    private final Scanner scanner;
    private final PrintStream out;
    private final AnsiStyles styles;
    private final ContextEditor contextEditor;

    ReviewTerminal(Scanner scanner, PrintStream out, boolean useColor) {
        this.scanner = scanner;
        this.out = out;
        this.styles = AnsiStyles.of(useColor);
        this.contextEditor = new ContextEditor(out, useColor);
    }

    /// Runs the full interactive review loop and returns the reviewer's decision.
    ///
    /// Displays the review header, output preview, and menu. Loops until the user
    /// selects a terminal action (approve, reject, or confirmed backtrack).
    ///
    /// @param data all data needed for the review UI, not null
    /// @return the reviewer's decision, never null
    ReviewDecision runReview(ReviewData data) {
        displayHeader(data);

        while (true) {
            displayMenu(data.allowBacktrack());
            String input = readInput().toUpperCase();

            switch (input) {
                case "A" -> {
                    return new ReviewDecision.Approve(null);
                }
                case "B" -> {
                    if (data.allowBacktrack()) {
                        ReviewDecision backtrack = handleBacktrack(data);
                        if (backtrack != null) return backtrack;
                    } else {
                        println(styles.warn("Backtracking is not allowed for this step."));
                    }
                }
                case "R" -> {
                    return handleReject();
                }
                case "V" -> displayDetailedOutput(data.output());
                case "H" -> displayHistory(data.historySteps(), data.nodeId());
                case "?" -> displayHelp();
                default -> println(styles.warn("Invalid option. Press ? for help."));
            }
        }
    }

    // — Display ———————————————————————————————————————————————————————————————

    private void displayHeader(ReviewData data) {
        println("");
        println(styles.boxTopWithLabel(styles.dim("review · ") + styles.bold(data.nodeId())));
        println("");

        println(String.format("  %-8s %s", "step", styles.bold(data.nodeId())));
        println(String.format("  %-8s %s", "status", formatStatus(data.status())));

        if (data.rubricScore() != null) {
            boolean passed = Boolean.TRUE.equals(data.rubricPassed());
            println(
                    String.format(
                            "  %-8s %s",
                            "rubric",
                            styles.successOrError(
                                    String.format(
                                            "%.0f/100  %s",
                                            data.rubricScore(), passed ? "PASSED" : "FAILED"),
                                    passed)));
        }

        println("");
        println(styles.gray("  output preview"));
        println(styles.separatorMid());
        String output = data.output() != null ? data.output() : "(no output)";
        String preview = output.length() > 500 ? output.substring(0, 500) + "..." : output;
        for (String line : preview.split("\n")) {
            println("  " + line);
        }
        println(styles.separatorMid());
        println("");
    }

    private void displayMenu(boolean allowBacktrack) {
        println(styles.bold("options"));
        println("  [a] " + styles.success("approve") + " and continue");
        if (allowBacktrack) {
            println("  [b] " + styles.warn("backtrack") + " to a previous step");
        }
        println("  [r] reject and end workflow");
        println(styles.gray("  [v] view detailed output"));
        println(styles.gray("  [h] view execution history"));
        println(styles.gray("  [?] help"));
        print("\n> ");
    }

    private void displayDetailedOutput(String output) {
        println("");
        println(styles.boxTopWithLabel(styles.dim("detailed output")));
        println("");

        if (output != null) {
            for (String line : output.split("\n")) {
                println("  " + line);
            }
        } else {
            println(styles.gray("  (no output)"));
        }
        println("");
        print("Press Enter to continue...");
        readInput();
    }

    private void displayHistory(List<ReviewData.StepInfo> steps, String currentNodeId) {
        println("");
        println(styles.boxTopWithLabel(styles.dim("execution history")));
        println("");

        if (steps == null || steps.isEmpty()) {
            println(styles.gray("  (no history)"));
        } else {
            for (int i = 0; i < steps.size(); i++) {
                ReviewData.StepInfo step = steps.get(i);
                boolean isCurrent = step.nodeId().equals(currentNodeId);
                String marker = isCurrent ? styles.arrow() : " ";
                boolean ok = "SUCCESS".equals(step.status());
                String status = styles.successOrError(ok ? "OK" : "FAIL", ok);
                println(String.format("%s %2d. %-30s [%s]", marker, i + 1, step.nodeId(), status));
            }
        }
        println("");
        print("Press Enter to continue...");
        readInput();
    }

    private void displayHelp() {
        println("");
        println(styles.boxTopWithLabel(styles.dim("help")));
        println("");
        println(styles.bold("commands"));
        println("  a  approve the current step and continue workflow execution");
        println("  b  backtrack to a previous step (if allowed)");
        println("     useful when you want to re-run a step with different inputs");
        println("  r  reject the workflow and terminate execution");
        println("  v  view the complete output of the current step");
        println("  h  view the execution history of all completed steps");
        println("  ?  display this help message");
        println("");
        println(styles.bold("backtracking"));
        println("  When backtracking, workflow execution will resume from the");
        println("  selected step. You can optionally edit the context variables");
        println("  (inputs) before re-execution.");
        println("");
        println(styles.bold("prompt editing"));
        println("  After selecting a backtrack target, you can edit the resolved");
        println("  prompt using your $EDITOR (defaults to vim). The editor shows");
        println("  the fully resolved prompt text that will be sent to the LLM.");
        println("  Lines starting with // are comments and will be ignored.");
        println("");
        print("Press Enter to continue...");
        readInput();
    }

    // — Backtrack ————————————————————————————————————————————————————————————

    private ReviewDecision handleBacktrack(ReviewData data) {
        List<ReviewData.StepInfo> steps = data.historySteps();

        if (steps == null || steps.size() <= 1) {
            println(styles.warn("No previous steps available to backtrack to."));
            return null;
        }

        List<ReviewData.StepInfo> validSteps = steps.subList(0, steps.size() - 1);

        println("");
        println(styles.boxTopWithLabel(styles.dim("backtrack target")));
        println("");

        for (int i = validSteps.size() - 1; i >= 0; i--) {
            ReviewData.StepInfo step = validSteps.get(i);
            int displayNum = validSteps.size() - i;
            boolean ok = "SUCCESS".equals(step.status());
            String status = styles.successOrError(ok ? "OK" : "FAIL", ok);
            println(String.format("  [%d] %s (%s)", displayNum, step.nodeId(), status));
        }

        println("");
        println("  [0] Cancel and return to review menu");
        print("\nSelect step number: ");

        String input = readInput();
        try {
            int choice = Integer.parseInt(input);
            if (choice == 0) return null;
            if (choice < 1 || choice > validSteps.size()) {
                println(styles.error("Invalid choice. Please select a number from the list."));
                return null;
            }

            ReviewData.StepInfo targetStep = validSteps.get(validSteps.size() - choice);

            print("Reason for backtracking (optional): ");
            String reason = readInput();
            if (reason.isBlank()) reason = "Manual backtrack by reviewer";

            Map<String, Object> editedContext = null;
            if (data.contextVariables() != null) {
                print("Edit prompt before re-execution? [y/N]: ");
                String editChoice = readInput().toLowerCase();
                if (editChoice.equals("y") || editChoice.equals("yes")) {
                    editedContext =
                            contextEditor.edit(
                                    data.contextVariables(),
                                    targetStep.promptTemplate(),
                                    targetStep.nodeId());
                    if (editedContext != null) {
                        println(styles.success("Prompt override applied."));
                    } else {
                        println(styles.warn("Prompt editing cancelled."));
                    }
                }
            }

            println(styles.warn("\nBacktracking to: " + targetStep.nodeId()));
            return new ReviewDecision.Backtrack(targetStep.nodeId(), editedContext, reason);

        } catch (NumberFormatException e) {
            println(styles.error("Please enter a valid number."));
            return null;
        }
    }

    // — Reject ———————————————————————————————————————————————————————————————

    private ReviewDecision handleReject() {
        println("");
        println(styles.error("Rejecting workflow..."));
        print("Reason for rejection (required): ");

        String reason = readInput();
        while (reason.isBlank()) {
            println(styles.warn("Reason is required for rejection."));
            print("Reason for rejection: ");
            reason = readInput();
        }
        return new ReviewDecision.Reject(reason);
    }

    // — Formatting ———————————————————————————————————————————————————————————

    private String formatStatus(String status) {
        return switch (status) {
            case "SUCCESS" -> styles.success("SUCCESS");
            case "FAILURE" -> styles.error("FAILURE");
            case "PENDING" -> styles.warn("PENDING");
            case "END" -> styles.accent("END");
            default -> status;
        };
    }

    private void println(String text) {
        out.println(text);
    }

    private void print(String text) {
        out.print(text);
        out.flush();
    }

    private String readInput() {
        return scanner.nextLine().trim();
    }
}
