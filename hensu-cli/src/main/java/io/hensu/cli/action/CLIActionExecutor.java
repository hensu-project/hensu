package io.hensu.cli.action;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.action.CommandRegistry;
import io.hensu.core.execution.action.CommandRegistry.CommandDefinition;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.template.TemplateResolver;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/// CLI implementation of {@link ActionExecutor} for mid-workflow actions.
///
/// ### Supported Actions
/// - **Notify** - Logs messages to console with channel prefix
/// - **Execute** - Runs shell commands from {@link CommandRegistry}
/// - **HttpCall** - Makes HTTP requests with template-resolved parameters
///
/// ### Security Model
/// Execute commands are **not** specified in the workflow DSL. They are loaded from a
/// `commands.yaml` file in the working directory to prevent command injection attacks.
/// Only pre-defined command IDs can be referenced in workflows.
///
/// ### Template Resolution
/// All action parameters support `{variable}` placeholder syntax, resolved from workflow context.
///
/// @implNote Thread-safe for action execution. CommandRegistry loading should be done
/// before parallel execution begins.
///
/// @see io.hensu.core.execution.action.Action
/// @see io.hensu.core.execution.action.CommandRegistry
@ApplicationScoped
public class CLIActionExecutor implements ActionExecutor {

    private static final Logger logger = Logger.getLogger(CLIActionExecutor.class.getName());

    private final TemplateResolver templateResolver = new SimpleTemplateResolver();
    private final HttpClient httpClient;
    private CommandRegistry commandRegistry;

    public CLIActionExecutor() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.commandRegistry = new CommandRegistry();
    }

    /// Load command registry from the specified working directory. Looks for commands.yaml in the
    /// directory.
    public void loadCommandRegistry(Path workingDirectory) {
        try {
            Path commandsFile = workingDirectory.resolve("commands.yaml");
            this.commandRegistry = CommandRegistry.loadFromFile(commandsFile);
            logger.info("Loaded command registry from: " + commandsFile);
        } catch (Exception e) {
            logger.warning("Failed to load command registry: " + e.getMessage());
            this.commandRegistry = new CommandRegistry();
        }
    }

    /// Set the command registry directly (for testing or programmatic use).
    public void setCommandRegistry(CommandRegistry registry) {
        this.commandRegistry = registry;
    }

    @Override
    public void setWorkingDirectory(Path workingDirectory) {
        loadCommandRegistry(workingDirectory);
    }

    @Override
    public ActionResult execute(Action action, Map<String, Object> context) {
        return switch (action) {
            case Action.Notify notify -> executeNotify(notify, context);
            case Action.Execute exec -> executeCommand(exec, context);
            case Action.HttpCall http -> executeHttpCall(http, context);
        };
    }

    private ActionResult executeNotify(Action.Notify notify, Map<String, Object> context) {
        String message = templateResolver.resolve(notify.getMessage(), context);
        String channel = notify.getChannel();

        logger.info("[" + channel.toUpperCase() + "] " + message);
        System.out.println("\n" + message);

        return ActionResult.success("Notification sent: " + message);
    }

    private ActionResult executeCommand(Action.Execute exec, Map<String, Object> context) {
        String commandId = exec.getCommandId();

        // Resolve command from registry
        if (!commandRegistry.hasCommand(commandId)) {
            String msg =
                    "Command not found in registry: '"
                            + commandId
                            + "'. "
                            + "Available commands: "
                            + commandRegistry.getCommandIds();
            logger.warning(msg);
            return ActionResult.failure(msg);
        }

        CommandDefinition cmdDef = commandRegistry.getCommand(commandId);
        String command = templateResolver.resolve(cmdDef.command(), context);

        logger.info("Executing command [" + commandId + "]: " + command);

        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.command(List.of("/bin/sh", "-c", command));
            pb.environment().putAll(cmdDef.environment());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.info("[CMD] " + line);
                }
            }

            boolean finished = process.waitFor(cmdDef.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ActionResult.failure("Command timed out after " + cmdDef.timeoutMs() + "ms");
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return ActionResult.success(
                        "Command completed successfully", output.toString().trim());
            } else {
                return ActionResult.failure("Command failed with exit code: " + exitCode);
            }

        } catch (Exception e) {
            logger.severe("Command execution failed: " + e.getMessage());
            return ActionResult.failure("Command execution failed: " + e.getMessage(), e);
        }
    }

    private ActionResult executeHttpCall(Action.HttpCall http, Map<String, Object> context) {
        String endpoint = templateResolver.resolve(http.getEndpoint(), context);
        String commandId = templateResolver.resolve(http.getCommandId(), context);
        String body =
                http.getBody() != null
                        ? templateResolver.resolve(http.getBody(), context)
                        : "{\"commandId\": \"" + commandId + "\"}";

        logger.info(
                "HTTP call: "
                        + http.getMethod()
                        + " "
                        + endpoint
                        + " (commandId: "
                        + commandId
                        + ")");

        try {
            HttpRequest.Builder requestBuilder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(endpoint))
                            .timeout(Duration.ofMillis(http.getTimeoutMs()));

            // Add headers
            for (Map.Entry<String, String> header : http.getHeaders().entrySet()) {
                requestBuilder.header(header.getKey(), header.getValue());
            }

            // Default content type if not specified
            if (!http.getHeaders().containsKey("Content-Type")) {
                requestBuilder.header("Content-Type", "application/json");
            }

            // Set method and body
            HttpRequest request =
                    switch (http.getMethod().toUpperCase()) {
                        case "GET" -> requestBuilder.GET().build();
                        case "DELETE" -> requestBuilder.DELETE().build();
                        case "POST" ->
                                requestBuilder
                                        .POST(HttpRequest.BodyPublishers.ofString(body))
                                        .build();
                        case "PUT" ->
                                requestBuilder
                                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                                        .build();
                        default ->
                                requestBuilder
                                        .method(
                                                http.getMethod(),
                                                HttpRequest.BodyPublishers.ofString(body))
                                        .build();
                    };

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            String responseBody = response.body();

            logger.info("HTTP response: " + statusCode);

            if (statusCode >= 200 && statusCode < 300) {
                return ActionResult.success(
                        "HTTP call successful (status: " + statusCode + ")", responseBody);
            } else {
                return ActionResult.failure(
                        "HTTP call failed (status: " + statusCode + "): " + responseBody);
            }

        } catch (Exception e) {
            logger.severe("HTTP call failed: " + e.getMessage());
            return ActionResult.failure("HTTP call failed: " + e.getMessage(), e);
        }
    }
}
