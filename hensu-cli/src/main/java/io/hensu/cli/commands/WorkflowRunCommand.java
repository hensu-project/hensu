package io.hensu.cli.commands;

import static io.hensu.cli.util.CliColors.*;

import io.hensu.core.HensuEnvironment;
import io.hensu.core.agent.AgentResponse;
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
            names = {"-w", "--watch"},
            description = "Watch mode for development")
    private boolean watch = false;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show agent inputs and outputs")
    private boolean verbose = false;

    @Option(
            names = {"-i", "--interactive"},
            description = "Enable interactive human review mode with manual backtracking")
    private boolean interactive = false;

    @Inject private HensuEnvironment environment;

    @Override
    protected void execute() {
        try {
            // Set interactive mode system property for CLIReviewManager
            if (interactive) {
                System.setProperty("hensu.review.interactive", "true");
            }

            Workflow workflow = getWorkflow(workflowName);

            System.out.printf(
                    "%n%s %sWorkflow loaded: %s%s%n",
                    successMark(), BOLD, workflow.getMetadata().getName(), NC);
            System.out.printf(
                    "%s  Agents: %d %s Nodes: %d%s%n%n",
                    GRAY, workflow.getAgents().size(), bullet(), workflow.getNodes().size(), NC);

            Map<String, Object> context = loadContext(contextInput);

            System.out.printf("%s  Starting workflow execution...%s%n", GRAY, NC);
            if (verbose) {
                System.out.printf("%s  (verbose mode enabled)%s%n", GRAY, NC);
            }
            if (interactive) {
                System.out.printf("%s  (interactive review mode enabled)%s%n", GRAY, NC);
            }
            System.out.println();

            // Configure action executor with working directory for command registry
            configureActionExecutor();

            WorkflowExecutor workflowExecutor = environment.getWorkflowExecutor();

            // Create listener for verbose output
            ExecutionListener listener = verbose ? createVerboseListener() : ExecutionListener.NOOP;
            ExecutionResult result = workflowExecutor.execute(workflow, context, listener);

            if (result instanceof ExecutionResult.Completed completed) {
                System.out.printf(
                        "%n%s %sWorkflow completed successfully!%s%n", successMark(), BOLD, NC);
                System.out.printf(
                        "%s  Status: %s%s %s Steps: %d %s Backtracks: %d%s%n",
                        GRAY,
                        NC,
                        success(completed.getExitStatus().toString()),
                        bullet(),
                        completed.getFinalState().getHistory().getSteps().size(),
                        bullet(),
                        completed.getFinalState().getHistory().getBacktracks().size(),
                        NC);

                if (!completed.getFinalState().getHistory().getBacktracks().isEmpty()) {
                    System.out.printf("%n%s  Backtrack Summary:%s%n", BOLD, NC);
                    completed
                            .getFinalState()
                            .getHistory()
                            .getBacktracks()
                            .forEach(
                                    bt ->
                                            System.out.printf(
                                                    "%s  %s%s %s %s%s %s(%s)%s%n",
                                                    GRAY,
                                                    NC,
                                                    bold(bt.getFrom()),
                                                    arrow(),
                                                    bold(bt.getTo()),
                                                    GRAY,
                                                    bt.getType(),
                                                    bt.getReason(),
                                                    NC));
                }
            } else if (result instanceof ExecutionResult.Rejected rejected) {
                System.out.printf("%n%s %sWorkflow rejected!%s%n", failMark(), BOLD, NC);
                System.out.printf("%s  Reason: %s%s%n", GRAY, NC, rejected.getReason());
            }
        } catch (Exception e) {
            System.err.printf(
                    "%s %sWorkflow execution failed:%s %s%n", failMark(), BOLD, NC, e.getMessage());
            e.printStackTrace();
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

    /// Creates a listener that prints agent inputs and outputs to stdout.
    private ExecutionListener createVerboseListener() {
        return new ExecutionListener() {
            @Override
            public void onAgentStart(String nodeId, String agentId, String prompt) {
                System.out.println(separatorTop());
                System.out.printf(
                        "  %s*%s %sINPUT%s %s[%s]%s %s %s%s%n",
                        BLUE, NC, BOLD, NC, GRAY, nodeId, NC, arrow(), agentId, NC);
                System.out.println(separatorMid());
                printIndented(prompt, false);
                System.out.println(separatorBottom());
                System.out.println();
            }

            @Override
            public void onAgentComplete(String nodeId, String agentId, AgentResponse response) {
                System.out.println(separatorTop());
                String statusColor = response.isSuccess() ? GREEN : RED;
                String status = statusColor + "OK" + NC;
                String leftArrow = statusColor + "←" + NC;
                System.out.printf(
                        "  %s*%s %sOUTPUT%s %s[%s]%s %s %s%s (%s)%n",
                        statusColor,
                        NC,
                        BOLD,
                        NC,
                        GRAY,
                        nodeId,
                        NC,
                        leftArrow,
                        agentId,
                        NC,
                        status);
                System.out.println(separatorMid());
                printIndented(response.getOutput(), true);
                System.out.println(separatorBottom());
                System.out.println();
            }

            private void printIndented(String text, boolean isOutput) {
                if (text == null || text.isEmpty()) {
                    System.out.printf("  %s(empty)%s%n", GRAY, NC);
                    return;
                }
                for (String line : text.split("\n")) {
                    String textColor = isOutput ? "" : GRAY;
                    System.out.printf("  %s%s%s%n", textColor, line, NC);
                }
            }
        };
    }
}
