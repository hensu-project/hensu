package io.hensu.cli.commands;

import io.hensu.cli.exception.UnsupportedWorkflowException;
import io.hensu.core.workflow.Workflow;
import io.hensu.dsl.WorkingDirectory;
import io.hensu.dsl.parsers.KotlinScriptParser;
import jakarta.inject.Inject;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import picocli.CommandLine.Option;

/// Base class for all workflow-related CLI commands.
///
/// Provides common functionality for workflow loading, working directory resolution,
/// and banner display. Subclasses implement specific command behavior in {@link #execute()}.
///
/// ### Working Directory Resolution
/// Priority order for determining the working directory:
/// 1. CLI option `-d` / `--working-dir`
/// 2. Config property `hensu.working.dir`
/// 3. Current directory (`.`)
///
/// ### Workflow Name Resolution
/// Priority order for determining the workflow name:
/// 1. CLI positional parameter
/// 2. Config property `hensu.workflow.file`
///
/// @implNote Subclasses must be package-private and annotated with `@Command`.
/// @see WorkflowRunCommand
/// @see WorkflowValidateCommand
/// @see WorkflowVisualizeCommand
public abstract class WorkflowCommand implements Runnable {

    private static final String[] BANNER = {
        "",
        "  _",
        " | |__    ___  _ __   ___  _   _",
        " | '_ \\  / _ \\| '_ \\ / __|| | | |",
        " | | | ||  __/| | | |\\__ \\| |_| |",
        " |_| |_| \\___||_| |_||___/ \\__,_|",
        "",
        " The Agentic Workflow Engine",
        ""
    };

    @Option(
            names = {"-d", "--working-dir"},
            description = "Working directory containing workflows/, prompts/, and rubrics/")
    protected Path workingDirPath;

    @Override
    public final void run() {
        for (String line : BANNER) {
            System.out.println(line);
        }
        execute();
    }

    protected abstract void execute();

    @Inject
    @ConfigProperty(name = "hensu.workflow.file")
    private String defaultWorkflowName;

    @Inject
    @ConfigProperty(name = "hensu.working.dir")
    private String defaultWorkingDir;

    @Inject private KotlinScriptParser kotlinParser;

    /// Loads and parses a workflow by name from the working directory.
    ///
    /// Resolves the workflow name using CLI argument or config default, then parses
    /// the Kotlin DSL file using {@link KotlinScriptParser}.
    ///
    /// @param workflowName workflow name (with or without `.kt` extension), may be null
    /// @return parsed workflow definition, never null
    /// @throws UnsupportedWorkflowException if workflow name is not specified and
    /// no default configured
    protected Workflow getWorkflow(String workflowName) throws UnsupportedWorkflowException {
        String effectiveWorkflowName = resolveWorkflowName(workflowName);
        if (effectiveWorkflowName == null) {
            System.err.println(
                    """
              No workflow name specified and no default configured.
              Usage: hensu run <workflow-name> [-d <working-dir>]
              Or set hensu.workflow.file in application.properties
              """);
            throw new UnsupportedWorkflowException("Workflow not found");
        }

        WorkingDirectory workingDir = getWorkingDirectory();
        System.out.println("Using working directory: " + workingDir.root());
        System.out.println("Compiling Kotlin DSL workflow: " + effectiveWorkflowName);

        return kotlinParser.parse(workingDir, effectiveWorkflowName);
    }

    /// Returns the effective working directory for workflow resolution.
    ///
    /// Resolution priority: CLI option `-d` > config property `hensu.working.dir` >
    /// current directory.
    ///
    /// @return working directory containing workflows/, prompts/, and rubrics/, never null
    protected WorkingDirectory getWorkingDirectory() {
        Path effectivePath;
        if (workingDirPath != null) {
            effectivePath = workingDirPath;
        } else if (defaultWorkingDir != null && !defaultWorkingDir.isBlank()) {
            effectivePath = Path.of(defaultWorkingDir);
        } else {
            effectivePath = Path.of(".");
        }
        return WorkingDirectory.of(effectivePath.toAbsolutePath());
    }

    /// Resolves the effective workflow name from CLI argument or config default.
    ///
    /// @param workflowName workflow name from CLI, may be null or blank
    /// @return resolved workflow name, or null if neither CLI nor config specifies one
    protected String resolveWorkflowName(String workflowName) {
        if (workflowName != null && !workflowName.isBlank()) {
            return workflowName;
        }
        return defaultWorkflowName != null && !defaultWorkflowName.isBlank()
                ? defaultWorkflowName
                : null;
    }
}
