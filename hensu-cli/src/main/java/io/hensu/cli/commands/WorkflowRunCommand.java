package io.hensu.cli.commands;

import io.hensu.cli.execution.VerboseExecutionListenerFactory;
import io.hensu.cli.ui.AnsiStyles;
import io.hensu.core.HensuEnvironment;
import io.hensu.core.agent.stub.StubResponseRegistry;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.workflow.Workflow;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// CLI command for executing workflows with agent orchestration.
///
/// Loads and executes a workflow, optionally with verbose output showing agent
/// inputs/outputs and interactive human review mode for manual approval/backtracking.
///
/// ### Usage
/// ```bash
/// hensu run [-d <working-dir>] [-v] [-i] [--no-color] [-c <context>] <workflow-name>
/// ```
///
/// ### Options
/// - `-v, --verbose` - Show agent inputs and outputs during execution
/// - `-i, --interactive` - Enable human review checkpoints with backtracking
/// - `-c, --context` - Provide initial context as JSON string or file path
/// - `--no-color` - Disable ANSI color output
///
/// @see io.hensu.core.execution.WorkflowExecutor
/// @see io.hensu.cli.review.CLIReviewManager
@Command(name = "run", description = "Run a workflow")
class WorkflowRunCommand extends WorkflowCommand {

    @Parameters(
            index = "0",
            description = "Workflow name (from workflows/ directory)",
            arity = "0..1")
    private String workflowName;

    @Option(
            names = {"-c", "--context"},
            description = "Context as JSON string or path to JSON/YAML file")
    private String contextInput;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show agent inputs and outputs")
    private boolean verbose = false;

    @Option(
            names = {"-i", "--interactive"},
            description = "Enable interactive human review mode with manual backtracking")
    private boolean interactive = false;

    @Option(
            names = {"--no-color"},
            description = "Disable colored output",
            negatable = true)
    private boolean color = true;

    @Inject private HensuEnvironment environment;
    @Inject private VerboseExecutionListenerFactory listenerFactory;

    @Override
    protected void execute() {
        AnsiStyles styles = AnsiStyles.of(color);

        try {
            // Set interactive mode system property for CLIReviewManager
            if (interactive) {
                System.setProperty("hensu.review.interactive", "true");
            }

            Workflow workflow = getWorkflow(workflowName);

            System.out.printf(
                    "%n%s %s%n",
                    styles.checkmark(),
                    styles.bold("Workflow loaded: " + workflow.getMetadata().getName()));
            System.out.printf(
                    "%s%n%n",
                    styles.gray(
                            "  Agents: "
                                    + workflow.getAgents().size()
                                    + " "
                                    + styles.bullet()
                                    + " Nodes: "
                                    + workflow.getNodes().size()));

            Map<String, Object> context = loadContext(contextInput);

            System.out.println(styles.gray("  Starting workflow execution..."));
            if (verbose) {
                System.out.println(styles.gray("  (verbose mode enabled)"));
            }
            if (interactive) {
                System.out.println(styles.gray("  (interactive review mode enabled)"));
            }
            System.out.println();

            // Configure stub registry with filesystem stubs directory
            configureStubsDirectory();

            // Configure action executor with working directory for command registry
            configureActionExecutor();

            WorkflowExecutor workflowExecutor = environment.getWorkflowExecutor();

            // Create listener for verbose output
            ExecutionListener listener =
                    verbose ? listenerFactory.create(workflow, color) : ExecutionListener.NOOP;
            ExecutionResult result = workflowExecutor.execute(workflow, context, listener);

            if (result instanceof ExecutionResult.Completed completed) {
                System.out.printf(
                        "%n%s %s%n",
                        styles.checkmark(), styles.bold("Workflow completed successfully!"));
                System.out.printf(
                        "  Status: %s %s Steps: %d %s Backtracks: %d%n",
                        styles.success(completed.getExitStatus().toString()),
                        styles.bullet(),
                        completed.getFinalState().getHistory().getSteps().size(),
                        styles.bullet(),
                        completed.getFinalState().getHistory().getBacktracks().size());

                if (!completed.getFinalState().getHistory().getBacktracks().isEmpty()) {
                    System.out.printf("%n%s%n", styles.bold("  Backtrack Summary:"));
                    completed
                            .getFinalState()
                            .getHistory()
                            .getBacktracks()
                            .forEach(
                                    bt ->
                                            System.out.printf(
                                                    "  %s %s %s %s%n",
                                                    styles.bold(bt.getFrom()),
                                                    styles.arrow(),
                                                    styles.bold(bt.getTo()),
                                                    styles.gray(
                                                            bt.getType()
                                                                    + "("
                                                                    + bt.getReason()
                                                                    + ")")));
                }
            } else if (result instanceof ExecutionResult.Rejected rejected) {
                System.out.printf(
                        "%n%s %s%n", styles.crossmark(), styles.bold("Workflow rejected!"));
                System.out.printf("  Reason: %s%n", rejected.getReason());
            }
        } catch (Exception e) {
            System.err.printf(
                    "%s %s %s%n",
                    styles.crossmark(), styles.bold("Workflow execution failed:"), e.getMessage());
            e.printStackTrace();
        }
    }

    /// Configures the stub response registry with the working directory's stubs folder,
    /// enabling filesystem-based stub resolution for CLI executions.
    private void configureStubsDirectory() {
        Path stubsDir = getWorkingDirectory().getStubsDir();
        if (Files.isDirectory(stubsDir)) {
            StubResponseRegistry.getInstance().setStubsDirectory(stubsDir);
        }
    }

    /// Configure the action executor with the workflow's working directory. This loads the command
    /// registry for Execute actions.
    private void configureActionExecutor() {
        var actionExecutor = environment.getActionExecutor();
        if (actionExecutor != null) {
            actionExecutor.setWorkingDirectory(getWorkingDirectory().root());
        }
    }

    /// Load context from JSON string or file path. If input starts with '{', treat as JSON string.
    /// Otherwise, treat as file path.
    private Map<String, Object> loadContext(String input) {
        if (input == null || input.isBlank()) {
            return new HashMap<>();
        }

        String json;
        if (input.trim().startsWith("{")) {
            // Direct JSON string
            json = input;
        } else {
            // File path
            try {
                json = Files.readString(Path.of(input));
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read context file: " + input, e);
            }
        }

        return parseSimpleJson(json);
    }

    /// Simple JSON parser for flat key-value maps. Handles: {"key": "value", "key2": "value2",
    /// "key3": 123, "key4": true}
    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new HashMap<>();

        // Remove outer braces and whitespace
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        // Pattern for key-value pairs
        Pattern stringPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");
        Pattern numberPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");
        Pattern boolPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*(true|false)");

        // Extract string values
        Matcher stringMatcher = stringPattern.matcher(json);
        while (stringMatcher.find()) {
            result.put(stringMatcher.group(1), stringMatcher.group(2));
        }

        // Extract number values (only if not already found as string)
        Matcher numberMatcher = numberPattern.matcher(json);
        while (numberMatcher.find()) {
            String key = numberMatcher.group(1);
            if (!result.containsKey(key)) {
                String value = numberMatcher.group(2);
                if (value.contains(".")) {
                    result.put(key, Double.parseDouble(value));
                } else {
                    result.put(key, Integer.parseInt(value));
                }
            }
        }

        // Extract boolean values (only if not already found)
        Matcher boolMatcher = boolPattern.matcher(json);
        while (boolMatcher.find()) {
            String key = boolMatcher.group(1);
            if (!result.containsKey(key)) {
                result.put(key, Boolean.parseBoolean(boolMatcher.group(2)));
            }
        }

        return result;
    }
}
