package io.hensu.cli.commands;

import io.hensu.cli.daemon.DaemonClient;
import io.hensu.cli.daemon.DaemonFrame;
import io.hensu.cli.execution.ExecutionSink;
import io.hensu.cli.execution.LocalExecutionSink;
import io.hensu.cli.execution.VerboseExecutionListenerFactory;
import io.hensu.cli.review.CLIReviewHandler;
import io.hensu.cli.review.DaemonClientReviewer;
import io.hensu.cli.ui.AnsiStyles;
import io.hensu.core.HensuEnvironment;
import io.hensu.core.agent.stub.StubResponseRegistry;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.workflow.Workflow;
import io.hensu.serialization.WorkflowSerializer;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// CLI command for executing workflows with agent orchestration.
///
/// Loads and executes a workflow either inline (when no daemon is running) or by
/// delegating to the background daemon (when one is alive on
/// {@link io.hensu.cli.daemon.DaemonPaths#socket()}).
///
/// ### Daemon mode
/// When the daemon is running, the command:
/// 1. Compiles the Kotlin DSL to a {@link Workflow} (client-side, as usual)
/// 2. Serializes the workflow to JSON and sends it to the daemon via Unix socket
/// 3. Streams output back to the terminal until the execution completes or Ctrl+C
///
/// Pressing Ctrl+C **detaches** the client — the execution keeps running in the
/// daemon. Use {@code hensu attach <id>} to reconnect, or {@code hensu cancel <id>}
/// to stop it.
///
/// ### Inline mode
/// When no daemon is running the workflow executes in-process, identical to the
/// previous behavior.
///
/// ### Usage
/// ```bash
/// hensu run [-d <working-dir>] [-v] [-i] [--no-color] [-c <context>] <workflow-name>
/// ```
///
/// @see io.hensu.core.execution.WorkflowExecutor
/// @see CLIReviewHandler
/// @see io.hensu.cli.daemon.DaemonServer
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

    @Option(
            names = {"--no-daemon"},
            description = "Force inline execution even if daemon is running")
    private boolean noDaemon = false;

    @Inject private HensuEnvironment environment;
    @Inject private VerboseExecutionListenerFactory listenerFactory;

    @Override
    protected void execute() {
        AnsiStyles styles = AnsiStyles.of(color);

        try {
            if (interactive) {
                System.setProperty("hensu.review.interactive", "true");
            }

            Workflow workflow = getWorkflow(workflowName);

            ExecutionSink sink = LocalExecutionSink.INSTANCE;
            @SuppressWarnings("resource") // wraps System.out — must not be closed
            PrintStream sinkOut = sink.out();
            sinkOut.printf(
                    "%n%s %s%n",
                    styles.checkmark(), styles.bold(workflow.getMetadata().getName() + " loaded"));
            sinkOut.printf("  agents   %d%n", workflow.getAgents().size());
            sinkOut.printf("  nodes    %d%n%n", workflow.getNodes().size());

            Map<String, Object> context = loadContext(contextInput);

            // — Daemon mode ——————————————————————————————————————————————
            if (!noDaemon && DaemonClient.isAlive()) {
                runViaDaemon(workflow, context, styles);
                return;
            }

            // — Inline mode ——————————————————————————————————————————————
            runInline(workflow, context, styles, sink);

        } catch (Exception e) {
            AnsiStyles s = AnsiStyles.of(color);
            System.err.printf(
                    "%s %s %s%n",
                    s.crossmark(), s.bold("Workflow execution failed:"), e.getMessage());
            e.printStackTrace();
        }
    }

    // — Daemon path ——————————————————————————————————————————————————————————

    private void runViaDaemon(Workflow workflow, Map<String, Object> context, AnsiStyles styles)
            throws IOException {

        String execId = UUID.randomUUID().toString();
        String workflowJson = WorkflowSerializer.toJson(workflow);

        // Build run frame
        DaemonFrame req = new DaemonFrame();
        req.type = "run";
        req.execId = execId;
        req.workflowId = workflow.getMetadata().getName();
        req.workflowJson = workflowJson;
        req.context = context;
        req.verbose = verbose;
        req.color = color;
        req.interactive = interactive;
        req.termWidth = terminalWidth();

        // Detach on Ctrl+C instead of killing the execution
        boolean[] completed = {false};
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    if (completed[0]) return;
                                    new DaemonClient().detach(execId);
                                    System.err.printf(
                                            "%nDetached. Execution %s is still running in the daemon.%n",
                                            execId);
                                    System.err.printf("Re-attach: hensu attach %s%n", execId);
                                    System.err.printf("Cancel:    hensu cancel %s%n", execId);
                                }));

        System.out.println(styles.gray("  Delegating to daemon"));
        System.out.printf("  exec     %s%n", styles.accent(execId));
        if (verbose) System.out.println(styles.gray("  verbose mode enabled"));
        if (interactive) System.out.println(styles.gray("  interactive review mode enabled"));
        System.out.println();

        DaemonClientReviewer reviewer = interactive ? new DaemonClientReviewer() : null;

        new DaemonClient()
                .run(
                        req,
                        (frame, reply) -> {
                            if ("exec_end".equals(frame.type)) completed[0] = true;
                            if ("review_request".equals(frame.type) && reviewer != null) {
                                handleReviewRequest(frame, reply, reviewer);
                                return;
                            }
                            handleDaemonFrame(frame, styles);
                        });
    }

    private void handleReviewRequest(
            DaemonFrame frame, Consumer<DaemonFrame> reply, DaemonClientReviewer reviewer) {
        var decision = reviewer.review(frame.reviewPayload);

        String decisionStr;
        String backtrackNode = null;
        String reason = null;
        Map<String, Object> editedContext = null;

        switch (decision) {
            case io.hensu.core.review.ReviewDecision.Approve _ -> decisionStr = "approve";
            case io.hensu.core.review.ReviewDecision.Reject r -> {
                decisionStr = "reject";
                reason = r.reason();
            }
            case io.hensu.core.review.ReviewDecision.Backtrack b -> {
                decisionStr = "backtrack";
                backtrackNode = b.targetStep();
                reason = b.reason();
                editedContext = b.contextEdits();
            }
        }

        reply.accept(
                DaemonFrame.reviewResponse(
                        frame.execId,
                        frame.reviewId,
                        decisionStr,
                        backtrackNode,
                        reason,
                        editedContext));
    }

    private void handleDaemonFrame(DaemonFrame frame, AnsiStyles styles) {
        switch (frame.type) {
            case "out" -> DaemonClient.printOutFrame(frame);
            case "exec_end" -> printCompletionSummary(frame, styles);
            case "error" ->
                    System.err.printf(
                            "%s %s%n",
                            styles.crossmark(),
                            styles.error(
                                    frame.message != null
                                            ? frame.message
                                            : "Unknown daemon error"));
            case "daemon_full" ->
                    System.err.println(
                            styles.warn(
                                    "Daemon is at capacity (max "
                                            + frame.maxConcurrent
                                            + " concurrent executions). Try again shortly."));
            default -> {
                /* ignore node_start / node_end / ping */
            }
        }
    }

    private void printCompletionSummary(DaemonFrame frame, AnsiStyles styles) {
        String status = frame.status != null ? frame.status : "UNKNOWN";
        boolean ok = status.startsWith("SUCCESS") || "COMPLETED".equals(status);
        System.out.printf(
                "%n%s %s%n",
                ok ? styles.checkmark() : styles.crossmark(),
                styles.bold(ok ? "Workflow completed successfully" : "Workflow ended"));
        System.out.printf("  status   %s%n", ok ? styles.success(status) : styles.error(status));
    }

    // — Inline path ——————————————————————————————————————————————————————————

    private void runInline(
            Workflow workflow, Map<String, Object> context, AnsiStyles styles, ExecutionSink sink)
            throws Exception {

        PrintStream out = sink.out();
        out.println(styles.gray("  Starting inline execution..."));
        if (verbose) out.println(styles.gray("  verbose mode enabled"));
        if (interactive) out.println(styles.gray("  interactive review mode enabled"));
        out.println();

        configureStubsDirectory();
        configureActionExecutor();

        WorkflowExecutor workflowExecutor = environment.getWorkflowExecutor();
        ExecutionListener listener =
                verbose
                        ? listenerFactory.create(workflow, out, color, terminalWidth())
                        : ExecutionListener.NOOP;

        ExecutionResult result = workflowExecutor.execute(workflow, context, listener);

        if (result instanceof ExecutionResult.Completed completed) {
            out.printf(
                    "%n%s %s%n",
                    styles.checkmark(), styles.bold("Workflow completed successfully"));
            out.printf("  status      %s%n", styles.success(completed.getExitStatus().toString()));
            out.printf(
                    "  steps       %d%n", completed.getFinalState().getHistory().getSteps().size());
            out.printf(
                    "  backtracks  %d%n",
                    completed.getFinalState().getHistory().getBacktracks().size());

            if (!completed.getFinalState().getHistory().getBacktracks().isEmpty()) {
                out.printf("%n%s%n", styles.bold("  Backtrack Summary:"));
                completed
                        .getFinalState()
                        .getHistory()
                        .getBacktracks()
                        .forEach(
                                bt ->
                                        out.printf(
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
            out.printf("%n%s %s%n", styles.crossmark(), styles.bold("Workflow rejected!"));
            out.printf("  Reason: %s%n", rejected.getReason());
        }
    }

    // — Helpers ——————————————————————————————————————————————————————————————

    private void configureStubsDirectory() {
        Path stubsDir = getWorkingDirectory().getStubsDir();
        if (Files.isDirectory(stubsDir)) {
            StubResponseRegistry.getInstance().setStubsDirectory(stubsDir);
        }
    }

    private void configureActionExecutor() {
        var actionExecutor = environment.getActionExecutor();
        if (actionExecutor != null) {
            actionExecutor.setWorkingDirectory(getWorkingDirectory().root());
        }
    }

    private Map<String, Object> loadContext(String input) {
        if (input == null || input.isBlank()) {
            return new HashMap<>();
        }
        String json;
        if (input.trim().startsWith("{")) {
            json = input;
        } else {
            try {
                json = Files.readString(Path.of(input));
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read context file: " + input, e);
            }
        }
        return parseSimpleJson(json);
    }

    /// Simple JSON parser for flat key-value maps.
    ///
    /// Handles: `{"key": "value", "key2": 123, "key3": true}`
    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new HashMap<>();
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        Pattern stringPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");
        Pattern numberPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");
        Pattern boolPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*(true|false)");

        Matcher m = stringPattern.matcher(json);
        while (m.find()) result.put(m.group(1), m.group(2));

        m = numberPattern.matcher(json);
        while (m.find()) {
            if (!result.containsKey(m.group(1))) {
                String v = m.group(2);
                result.put(
                        m.group(1), v.contains(".") ? Double.parseDouble(v) : Integer.parseInt(v));
            }
        }

        m = boolPattern.matcher(json);
        while (m.find()) {
            if (!result.containsKey(m.group(1)))
                result.put(m.group(1), Boolean.parseBoolean(m.group(2)));
        }

        return result;
    }

    private int terminalWidth() {
        String cols = System.getenv("COLUMNS");
        if (cols != null) {
            try {
                return Integer.parseInt(cols.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 80;
    }
}
