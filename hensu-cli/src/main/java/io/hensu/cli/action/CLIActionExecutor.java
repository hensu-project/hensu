package io.hensu.cli.action;

import io.hensu.cli.sandbox.CommandResult;
import io.hensu.cli.sandbox.CommandRunner;
import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.action.ActionHandler;
import io.hensu.core.execution.action.CommandDefinition;
import io.hensu.core.execution.action.CommandRegistry;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.template.TemplateResolver;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/// CLI implementation of {@link ActionExecutor} for mid-workflow actions.
///
/// ### Supported Actions
/// - **Send** - Delegates to registered {@link ActionHandler} implementations
/// - **Execute** - Runs a command from {@link CommandRegistry} through {@link CommandRunner}
///
/// ### Security Model
/// - **Execute**: Commands are loaded from `commands.yaml`, not specified in DSL,
///   and run through the same {@link CommandRunner} agent-invoked tools use – argv
///   binding, a rebuilt environment and OS containment apply to workflow-authored
///   actions exactly as they do to agent-chosen ones
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
/// Send payloads support `{variable}` placeholder syntax, resolved from workflow context.
/// Execute actions do not: a command's `{param}` placeholders are bound as whole
/// argv elements by {@link CommandRunner}, from the state variables whose names
/// match the command's declared parameters.
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
    private volatile CommandRegistry commandRegistry;
    private volatile CommandRunner commandRunner;
    private volatile Path workingDirectory;

    /// Creates an executor with an empty catalog and the host's command runner.
    public CLIActionExecutor() {
        this.commandRegistry = new CommandRegistry();
        this.commandRunner = CommandRunner.forHost();
        this.workingDirectory = Path.of("").toAbsolutePath();
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
    ///
    /// @param registry the catalog to use, not null
    public void setCommandRegistry(CommandRegistry registry) {
        this.commandRegistry = registry;
    }

    /// Set the command runner directly, for tests that need a specific sandbox
    /// backend or a fixed host environment.
    ///
    /// @param runner the runner to execute commands through, not null
    public void setCommandRunner(CommandRunner runner) {
        this.commandRunner = runner;
    }

    @Override
    public void setWorkingDirectory(Path workingDirectory) {
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
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

        Map<String, Object> effectivePayload =
                send.isRawPayload()
                        ? send.getPayload()
                        : templateResolver.resolvePayload(send.getPayload(), context);

        return handler.execute(effectivePayload, context);
    }

    private ActionResult executeCommand(Action.Execute exec, Map<String, Object> context) {
        String commandId = exec.getCommandId();

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

        CommandDefinition definition = commandRegistry.getCommand(commandId);
        CommandResult result = commandRunner.run(definition, context, workingDirectory);

        if (result.success()) {
            return ActionResult.success("Command completed successfully", result.output());
        }
        String message = "Command '" + commandId + "' " + result.status() + ": " + result.message();
        logger.warning(message);
        return ActionResult.failure(message);
    }
}
