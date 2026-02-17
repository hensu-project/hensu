package io.hensu.core.execution.action;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Registry for executable commands. Commands are loaded from a YAML file to prevent command
/// injection vulnerabilities. File `commands.yaml` should be located in working directory.
///
/// The workflow DSL references commands by ID only. Actual commands are resolved from this
/// registry at execution time.
///
/// ### Example
/// {@snippet lang=yaml:
/// commands:
///   deploy-prod:
///     command: "./deploy.sh --env prod"
///     timeout: 60000
///   run-tests:
///     command: "./gradlew test"
///     timeout: 120000
///     env:
///       CI: "true"
/// }
public class CommandRegistry {

    private static final Logger logger = Logger.getLogger(CommandRegistry.class.getName());

    private final Map<String, CommandDefinition> commands;

    public CommandRegistry() {
        this.commands = new HashMap<>();
    }

    public CommandRegistry(Map<String, CommandDefinition> commands) {
        this.commands = new HashMap<>(commands);
    }

    /// Load commands from a YAML file.
    public static CommandRegistry loadFromFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            logger.warning("Commands file not found: " + path + ". Using empty registry.");
            return new CommandRegistry();
        }

        String content = Files.readString(path);
        return parseYaml(content);
    }

    /// Simple YAML parser for commands file. Supports the structure: commands: command-id: command:
    /// "actual command" timeout: 30000 env: KEY: "value"
    private static CommandRegistry parseYaml(String content) {
        Map<String, CommandDefinition> commands = new HashMap<>();

        // Simple line-by-line parsing
        String[] lines = content.split("\n");
        String currentCommandId = null;
        String currentCommand = null;
        long currentTimeout = 30000;
        Map<String, String> currentEnv = new HashMap<>();
        boolean inEnvSection = false;

        Pattern commandIdPattern = Pattern.compile("^ {2}([\\w-]+):\\s*$");
        Pattern commandPattern = Pattern.compile("^ {4}command:\\s*[\"']?(.+?)[\"']?\\s*$");
        Pattern timeoutPattern = Pattern.compile("^ {4}timeout:\\s*(\\d+)\\s*$");
        Pattern envKeyPattern = Pattern.compile("^ {6}([\\w_]+):\\s*[\"']?(.+?)[\"']?\\s*$");
        Pattern envSectionPattern = Pattern.compile("^ {4}env:\\s*$");

        for (String line : lines) {
            // Skip comments and empty lines
            if (line.trim().startsWith("#") || line.trim().isEmpty()) {
                continue;
            }

            // Check for new command ID
            Matcher idMatcher = commandIdPattern.matcher(line);
            if (idMatcher.matches()) {
                // Save previous command if exists
                if (currentCommandId != null && currentCommand != null) {
                    commands.put(
                            currentCommandId,
                            new CommandDefinition(
                                    currentCommand, currentTimeout, Map.copyOf(currentEnv)));
                }
                // Start new command
                currentCommandId = idMatcher.group(1);
                currentCommand = null;
                currentTimeout = 30000;
                currentEnv = new HashMap<>();
                inEnvSection = false;
                continue;
            }

            // Check for command value
            Matcher cmdMatcher = commandPattern.matcher(line);
            if (cmdMatcher.matches()) {
                currentCommand = cmdMatcher.group(1);
                inEnvSection = false;
                continue;
            }

            // Check for timeout
            Matcher timeoutMatcher = timeoutPattern.matcher(line);
            if (timeoutMatcher.matches()) {
                currentTimeout = Long.parseLong(timeoutMatcher.group(1));
                inEnvSection = false;
                continue;
            }

            // Check for env section
            if (envSectionPattern.matcher(line).matches()) {
                inEnvSection = true;
                continue;
            }

            // Check for env key-value
            if (inEnvSection) {
                Matcher envMatcher = envKeyPattern.matcher(line);
                if (envMatcher.matches()) {
                    currentEnv.put(envMatcher.group(1), envMatcher.group(2));
                }
            }
        }

        // Save last command
        if (currentCommandId != null && currentCommand != null) {
            commands.put(
                    currentCommandId,
                    new CommandDefinition(currentCommand, currentTimeout, Map.copyOf(currentEnv)));
        }

        logger.info("Loaded " + commands.size() + " commands from registry");
        return new CommandRegistry(commands);
    }

    /// Get a command definition by ID.
    ///
    /// @throws IllegalArgumentException if command ID not found
    public CommandDefinition getCommand(String commandId) {
        CommandDefinition def = commands.get(commandId);
        if (def == null) {
            throw new IllegalArgumentException(
                    "Command not found in registry: '"
                            + commandId
                            + "'. "
                            + "Available commands: "
                            + commands.keySet());
        }
        return def;
    }

    /// Check if a command ID exists in the registry.
    public boolean hasCommand(String commandId) {
        return commands.containsKey(commandId);
    }

    /// Get all registered command IDs.
    public java.util.Set<String> getCommandIds() {
        return Collections.unmodifiableSet(commands.keySet());
    }

    /// Register a command programmatically.
    public void registerCommand(String id, CommandDefinition definition) {
        commands.put(id, definition);
    }

    /// Definition of an executable command.
    public record CommandDefinition(
            String command, long timeoutMs, Map<String, String> environment) {
        public CommandDefinition(String command) {
            this(command, 30000, Map.of());
        }

        public CommandDefinition(String command, long timeoutMs) {
            this(command, timeoutMs, Map.of());
        }
    }
}
