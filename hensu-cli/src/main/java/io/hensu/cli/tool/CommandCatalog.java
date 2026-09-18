package io.hensu.cli.tool;

import io.hensu.core.execution.action.CommandRegistry;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.logging.Logger;

/// The loaded `commands.yaml` and the directory it was loaded from, shared by
/// everything that runs a command.
///
/// Two callers need the same catalog: the action path, where a workflow's
/// `execute(...)` names an entry, and the tool path, where an agent picks one.
/// Before this bean the registry lived inside the action executor, which meant
/// the tool provider would have had to load the file a second time – two
/// readers of one security-relevant file, free to disagree about what the
/// deployment granted.
///
/// A run sets the working directory once, at start-up, and the catalog reloads
/// with it.
///
/// ### Contracts
/// - **Precondition**: none; a missing `commands.yaml` yields an empty catalog
/// - **Postcondition**: {@link #registry()} and {@link #workingDirectory()}
///   always describe the same directory
///
/// @implNote **Mutable.** The working directory is set once per run, before any
/// execution starts, and both fields are volatile so the executor thread and
/// the tool threads observe the same catalog.
/// @see CommandToolProvider for the agent-facing half
/// @see io.hensu.cli.action.CLIActionExecutor for the workflow-authored half
@Singleton
public class CommandCatalog {

    private static final Logger logger = Logger.getLogger(CommandCatalog.class.getName());

    /// Name of the file a deployment declares its commands in.
    public static final String FILE_NAME = "commands.yaml";

    private volatile CommandRegistry registry = new CommandRegistry();
    private volatile Path workingDirectory = Path.of("").toAbsolutePath();

    /// Returns the catalog currently in force.
    ///
    /// @return the loaded registry, never null (empty when nothing was declared)
    public CommandRegistry registry() {
        return registry;
    }

    /// Returns the directory commands run in and paths resolve against.
    ///
    /// @return the absolute working directory, never null
    public Path workingDirectory() {
        return workingDirectory;
    }

    /// Points the catalog at a directory and loads its `commands.yaml`.
    ///
    /// A malformed file leaves the run with an empty catalog rather than
    /// aborting it: the deployment granted nothing the engine could verify, so
    /// the honest outcome is a run with no commands, whose capability gaps name
    /// what was missing.
    ///
    /// @param directory the directory to run commands in, not null
    /// @apiNote **Side effects**: reads `commands.yaml`, logs what it loaded or
    ///     why it could not.
    public void setWorkingDirectory(Path directory) {
        Path resolved = directory.toAbsolutePath().normalize();
        this.workingDirectory = resolved;
        Path file = resolved.resolve(FILE_NAME);
        try {
            this.registry = CommandRegistry.loadFromFile(file, resolved);
            logger.info("Loaded command catalog from " + file);
        } catch (Exception e) {
            logger.warning("Could not load " + file + ": " + e.getMessage());
            this.registry = new CommandRegistry();
        }
    }

    /// Replaces the catalog without reading a file.
    ///
    /// @param registry the catalog to use, not null
    /// @apiNote Test seam. Production code reaches the catalog through
    ///     {@link #setWorkingDirectory}.
    public void setRegistry(CommandRegistry registry) {
        this.registry = registry;
    }
}
