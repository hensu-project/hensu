package io.hensu.cli.action;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.action.ActionHandler;
import io.hensu.core.execution.action.CommandRegistry;
import io.hensu.core.execution.action.CommandRegistry.CommandDefinition;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.template.TemplateResolver;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/// CLI implementation of {@link ActionExecutor} for mid-workflow actions.
///
/// ### Supported Actions
/// - **Send** - Delegates to registered {@link ActionHandler} implementations
/// - **Execute** - Runs shell commands from {@link CommandRegistry}
///
/// ### Security Model
/// - **Execute**: Commands are loaded from `commands.yaml`, not specified in DSL
/// - **Send**: All configuration is encapsulated in user-implemented handlers
///
/// Both patterns keep sensitive data (credentials, endpoints) out of workflow files.
///
/// ### Action Handler Registration
/// {@snippet :
/// CLIActionExecutor executor = new CLIActionExecutor();
/// executor.registerHandler(new SlackHandler(webhookUrl));
/// executor.registerHandler(new GitHubDispatchHandler(token));
/// }
///
/// ### Template Resolution
/// All action parameters support `{variable}` placeholder syntax, resolved from workflow context.
///
/// @implNote Thread-safe. Uses ConcurrentHashMap for handler storage.
///
/// @see io.hensu.core.execution.action.Action
/// @see io.hensu.core.execution.action.CommandRegistry
/// @see io.hensu.core.execution.action.ActionHandler
@ApplicationScoped
public class CLIActionExecutor implements ActionExecutor {

    private static final Logger logger = Logger.getLogger(CLIActionExecutor.class.getName());

    private final TemplateResolver templateResolver = new SimpleTemplateResolver();
    private final Map<String, ActionHandler> handlers = new ConcurrentHashMap<>();
    private CommandRegistry commandRegistry;

    public CLIActionExecutor() {
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
    public void registerHandler(ActionHandler handler) {
        String handlerId = handler.getHandlerId();
        handlers.put(handlerId, handler);
        logger.info("Registered action handler: " + handlerId);
    }

    @Override
    public Optional<ActionHandler> getHandler(String handlerId) {
        return Optional.ofNullable(handlers.get(handlerId));
    }

    @Override
    public ActionResult execute(Action action, Map<String, Object> context) {
        return switch (action) {
            case Action.Send send -> executeSend(send, context);
            case Action.Execute exec -> executeCommand(exec, context);
        };
    }

    private ActionResult executeSend(Action.Send send, Map<String, Object> context) {
        String handlerId = send.getHandlerId();

        ActionHandler handler = handlers.get(handlerId);
        if (handler == null) {
            String msg =
                    "Action handler not found: '"
                            + handlerId
                            + "'. "
                            + "Registered handlers: "
                            + handlers.keySet();
            logger.warning(msg);
            return ActionResult.failure(msg);
        }

        logger.info("Executing send action via handler: " + handlerId);

        // Resolve template variables in payload values
        Map<String, Object> resolvedPayload = resolvePayload(send.getPayload(), context);

        return handler.execute(resolvedPayload, context);
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

    /// Resolves template variables in payload string values.
    private Map<String, Object> resolvePayload(
            Map<String, Object> payload, Map<String, Object> context) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String stringValue) {
                resolved.put(entry.getKey(), templateResolver.resolve(stringValue, context));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }
}
