package io.hensu.cli.review;

import io.hensu.cli.ui.AnsiStyles;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

/// CLI-based human review handler for interactive workflow checkpoints.
///
/// Provides a terminal-based interface for human-in-the-loop workflow review with support for:
/// - **Approve** - Continue workflow execution
/// - **Backtrack** - Return to any previous step with optional prompt editing
/// - **Reject** - Terminate workflow with a reason
/// - **View** - Inspect detailed output and execution history
///
/// ### Enabling Interactive Mode
/// Set the system property `hensu.review.interactive=true` to enable prompts.
/// When disabled (default), all reviews are automatically approved.
///
/// ### Prompt Editing
/// During backtrack, users can edit prompts using their `$EDITOR` (defaults to vim).
/// The editor displays available context variables and ignores comment lines starting with `#`.
///
/// @implNote **Not thread-safe**. Designed for single-threaded CLI usage.
/// Reads from Scanner, writes to PrintStream.
///
/// @see io.hensu.core.review.ReviewHandler
/// @see io.hensu.core.review.ReviewDecision
public class CLIReviewManager implements ReviewHandler {

    /// System property key to enable/disable interactive mode at runtime.
    public static final String INTERACTIVE_PROPERTY = "hensu.review.interactive";

    private final Scanner scanner;
    private final PrintStream out;
    private final AnsiStyles styles;

    public CLIReviewManager() {
        this(new Scanner(System.in), System.out, true);
    }

    public CLIReviewManager(Scanner scanner, PrintStream out, boolean useColor) {
        this.scanner = scanner;
        this.out = out;
        this.styles = AnsiStyles.of(useColor);
    }

    /// Check if interactive mode is enabled via system property.
    private boolean isInteractive() {
        return Boolean.parseBoolean(System.getProperty(INTERACTIVE_PROPERTY, "false"));
    }

    @Override
    public ReviewDecision requestReview(
            Node node,
            NodeResult result,
            HensuState state,
            ExecutionHistory history,
            ReviewConfig config,
            Workflow workflow) {
        // Auto-approve if interactive mode is not enabled
        if (!isInteractive()) {
            return new ReviewDecision.Approve(null);
        }

        displayReviewHeader(node, result, state);

        while (true) {
            displayMenu(config);
            String input = readInput().toUpperCase();

            switch (input) {
                case "A" -> {
                    return new ReviewDecision.Approve(null);
                }
                case "B" -> {
                    if (config.isAllowBacktrack()) {
                        ReviewDecision backtrackDecision =
                                handleBacktrack(history, state, workflow);
                        if (backtrackDecision != null) {
                            return backtrackDecision;
                        }
                    } else {
                        println(styles.warn("Backtracking is not allowed for this step."));
                    }
                }
                case "R" -> {
                    return handleReject();
                }
                case "V" -> displayDetailedOutput(result);
                case "H" -> displayHistory(history, node.getId());
                case "?" -> displayHelp();
                default -> println(styles.warn("Invalid option. Press ? for help."));
            }
        }
    }

    private void displayReviewHeader(Node node, NodeResult result, HensuState state) {
        println("");
        println(styles.separatorTop());
        println(styles.bold(styles.center("HUMAN REVIEW CHECKPOINT", 62)));
        println(styles.separatorBottom());
        println("");

        // Node info
        println(styles.bold("Step: ") + node.getId());
        println(styles.bold("Status: ") + formatStatus(result.getStatus()));

        // Rubric score if available
        RubricEvaluation rubricEval = state.getRubricEvaluation();
        if (rubricEval != null) {
            String passStatus = rubricEval.isPassed() ? "PASSED" : "FAILED";
            println(
                    styles.bold("Rubric Score: ")
                            + styles.successOrError(
                                    String.format(
                                            "%.0f/100 (%s)", rubricEval.getScore(), passStatus),
                                    rubricEval.isPassed()));
        }

        println("");

        // Output preview (truncated)
        println(styles.bold("Output Preview:"));
        println(styles.dim("-".repeat(62)));
        String output = result.getOutput() != null ? result.getOutput().toString() : "(no output)";
        String preview = output.length() > 500 ? output.substring(0, 500) + "..." : output;
        println(preview);
        println(styles.dim("-".repeat(62)));
        println("");
    }

    private void displayMenu(ReviewConfig config) {
        println(styles.bold("Review Options:"));
        println("  [A] " + styles.success("Approve") + " and continue");
        if (config.isAllowBacktrack()) {
            println("  [B] " + styles.warn("Backtrack") + " to a previous step");
        }
        println("  [R] Reject and end workflow");
        println(styles.gray("  [V] View detailed output"));
        println(styles.gray("  [H] View execution history"));
        println(styles.gray("  [?] Help"));
        print("\n> ");
    }

    private ReviewDecision handleBacktrack(
            ExecutionHistory history, HensuState state, Workflow workflow) {
        List<ExecutionStep> steps = history.getSteps();

        if (steps.size() <= 1) {
            println(styles.warn("No previous steps available to backtrack to."));
            return null;
        }

        // Get all valid backtrack points (exclude current step)
        List<ExecutionStep> validSteps = new ArrayList<>(steps.subList(0, steps.size() - 1));

        println("");
        println(styles.separatorTop());
        println(styles.bold(styles.center("SELECT BACKTRACK TARGET", 62)));
        println(styles.separatorBottom());
        println("");

        // Display available steps (newest first for easier selection)
        for (int i = validSteps.size() - 1; i >= 0; i--) {
            ExecutionStep step = validSteps.get(i);
            int displayNum = validSteps.size() - i;
            String status =
                    styles.successOrError(
                            step.getResult().getStatus() == ResultStatus.SUCCESS ? "OK" : "FAIL",
                            step.getResult().getStatus() == ResultStatus.SUCCESS);
            println(String.format("  [%d] %s (%s)", displayNum, step.getNodeId(), status));
        }

        println("");
        println("  [0] Cancel and return to review menu");
        print("\nSelect step number: ");

        String input = readInput();

        try {
            int choice = Integer.parseInt(input);

            if (choice == 0) {
                return null; // Cancel
            }

            if (choice < 1 || choice > validSteps.size()) {
                println(styles.error("Invalid choice. Please select a number from the list."));
                return null;
            }

            // Convert display number back to step index
            ExecutionStep targetStep = validSteps.get(validSteps.size() - choice);

            // Ask for reason
            print("Reason for backtracking (optional): ");
            String reason = readInput();
            if (reason.isBlank()) {
                reason = "Manual backtrack by reviewer";
            }

            // Ask if user wants to edit the prompt
            String editedPrompt = null;
            print("Edit prompt before re-execution? [y/N]: ");
            String editChoice = readInput().toLowerCase();
            String targetNodeId = targetStep.getNodeId();
            if (editChoice.equals("y") || editChoice.equals("yes")) {
                Node node = workflow.getNodes().get(targetNodeId);
                Optional<String> optionalPrompt =
                        Optional.ofNullable(
                                node instanceof StandardNode
                                        ? ((StandardNode) node).getPrompt()
                                        : null);
                editedPrompt = editPromptWithVim(targetNodeId, state, optionalPrompt.orElse(""));
                if (editedPrompt != null) {
                    println(styles.success("Prompt updated."));
                } else {
                    println(styles.warn("Prompt editing canceled or failed."));
                }
            }

            println(styles.warn("\nBacktracking to: " + targetNodeId));

            return new ReviewDecision.Backtrack(targetNodeId, state, reason, editedPrompt);

        } catch (NumberFormatException e) {
            println(styles.error("Please enter a valid number."));
            return null;
        }
    }

    /// Opens vim to edit the prompt for a step. Creates a temporary file with context information
    /// and the prompt template.
    ///
    /// @param nodeId The node being re-executed
    /// @param state  Current state with context variables
    /// @param prompt Prompt for edit
    /// @return The edited prompt, or null if editing was canceled/failed
    private String editPromptWithVim(String nodeId, HensuState state, String prompt) {
        try {
            // Create temporary file with prompt template
            Path tempFile = Files.createTempFile("hensu-prompt-", ".txt");

            // Build the template content
            StringBuilder template = new StringBuilder();
            template.append("# Hensu Prompt Editor\n");
            template.append("# Node: ").append(nodeId).append("\n");
            template.append("# \n");
            template.append("# Lines starting with # are comments and will be ignored.\n");
            template.append("# Write your new prompt below. Save and exit vim to apply.\n");
            template.append("# Leave empty or delete all non-comment lines to cancel.\n");
            template.append("# \n");
            template.append("# Available context variables:\n");

            // Show available context variables
            if (state.getContext() != null && !state.getContext().isEmpty()) {
                state.getContext()
                        .forEach(
                                (key, value) -> {
                                    String valuePreview = value != null ? value.toString() : "null";
                                    if (valuePreview.length() > 50) {
                                        valuePreview = valuePreview.substring(0, 50) + "...";
                                    }
                                    template.append("#   {")
                                            .append(key)
                                            .append("} = ")
                                            .append(valuePreview)
                                            .append("\n");
                                });
            } else {
                template.append("#   (no context variables available)\n");
            }

            template.append("# \n");
            template.append("# ─────────── EDIT YOUR PROMPT BELOW THIS LINE ───────────\n");
            template.append(prompt);
            template.append("\n");

            // Write template to file
            Files.writeString(tempFile, template.toString());

            // Get the editor from environment or default to vim
            String editor = System.getenv("EDITOR");
            if (editor == null || editor.isBlank()) {
                editor = "vim";
            }

            // Open editor
            ProcessBuilder pb = new ProcessBuilder(editor, tempFile.toString());
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                println(styles.error("Editor exited with error code: " + exitCode));
                Files.deleteIfExists(tempFile);
                return null;
            }

            // Read the edited content
            String content = Files.readString(tempFile);
            Files.deleteIfExists(tempFile);

            // Extract non-comment lines
            StringBuilder editedPrompt = new StringBuilder();
            for (String line : content.split("\n")) {
                if (!line.startsWith("#")) {
                    editedPrompt.append(line).append("\n");
                }
            }

            String result = editedPrompt.toString().trim();
            return result.isEmpty() ? null : result;

        } catch (IOException | InterruptedException e) {
            println(styles.error("Error editing prompt: " + e.getMessage()));
            return null;
        }
    }

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

    private void displayDetailedOutput(NodeResult result) {
        println("");
        println(styles.separatorTop());
        println(styles.bold(styles.center("DETAILED OUTPUT", 62)));
        println(styles.separatorBottom());
        println("");

        if (result.getOutput() != null) {
            println(result.getOutput().toString());
        } else {
            println(styles.gray("(no output)"));
        }

        if (!result.getMetadata().isEmpty()) {
            println("");
            println(styles.bold("Metadata:"));
            result.getMetadata().forEach((key, value) -> println("  " + key + ": " + value));
        }

        println("");
        print("Press Enter to continue...");
        readInput();
    }

    private void displayHistory(ExecutionHistory history, String currentNodeId) {
        println("");
        println(styles.separatorTop());
        println(styles.bold(styles.center("EXECUTION HISTORY", 62)));
        println(styles.separatorBottom());
        println("");

        List<ExecutionStep> steps = history.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            ExecutionStep step = steps.get(i);
            boolean isCurrent = step.getNodeId().equals(currentNodeId);
            String marker = isCurrent ? styles.success("->") : "  ";
            String status =
                    styles.successOrError(
                            step.getResult().getStatus() == ResultStatus.SUCCESS ? "OK" : "FAIL",
                            step.getResult().getStatus() == ResultStatus.SUCCESS);
            String timestamp = step.getTimestamp() != null ? step.getTimestamp().toString() : "";

            println(
                    String.format(
                            "%s %2d. %-30s [%s] %s",
                            marker, i + 1, step.getNodeId(), status, styles.gray(timestamp)));
        }

        // Display backtrack events if any
        if (!history.getBacktracks().isEmpty()) {
            println("");
            println(styles.warn("Backtrack Events:"));
            history.getBacktracks()
                    .forEach(
                            bt ->
                                    println(
                                            "  "
                                                    + bt.getFrom()
                                                    + " -> "
                                                    + bt.getTo()
                                                    + " ("
                                                    + bt.getReason()
                                                    + ")"));
        }

        println("");
        print("Press Enter to continue...");
        readInput();
    }

    private void displayHelp() {
        println("");
        println(styles.separatorTop());
        println(styles.bold(styles.center("REVIEW HELP", 62)));
        println(styles.separatorBottom());
        println("");
        println(styles.bold("Commands:"));
        println("  A - Approve the current step and continue workflow execution");
        println("  B - Backtrack to a previous step (if allowed)");
        println("      Useful when you want to re-run a step with different inputs");
        println("  R - Reject the workflow and terminate execution");
        println("  V - View the complete output of the current step");
        println("  H - View the execution history of all completed steps");
        println("  ? - Display this help message");
        println("");
        println(styles.bold("Backtracking:"));
        println("  When backtracking, workflow execution will resume from the");
        println("  selected step. The current state context is preserved, allowing");
        println("  the re-executed step to potentially produce different output.");
        println("");
        println(styles.bold("Prompt Editing:"));
        println("  After selecting a backtrack target, you can optionally edit the");
        println("  prompt using your $EDITOR (defaults to vim). The editor shows:");
        println("  - Available context variables and their current values");
        println("  - Lines starting with # are comments and will be ignored");
        println("  - Write your new prompt, save, and exit to apply changes");
        println("");
        print("Press Enter to continue...");
        readInput();
    }

    private String formatStatus(ResultStatus status) {
        return switch (status) {
            case SUCCESS -> styles.success("SUCCESS");
            case FAILURE -> styles.error("FAILURE");
            case PENDING -> styles.warn("PENDING");
            case END -> styles.accent("END");
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
