package io.hensu.cli.commands;

import io.hensu.cli.exception.UnsupportedWorkflowException;
import io.hensu.cli.workflow.SubWorkflowLoader;
import io.hensu.core.workflow.Workflow;
import io.hensu.dsl.WorkingDirectory;
import io.hensu.dsl.parsers.KotlinScriptParser;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import picocli.CommandLine.Option;

/// Base class for all local workflow-related CLI commands.
///
/// Provides working directory resolution and workflow loading for commands that
/// operate on local Kotlin DSL files. Subclasses implement specific command
/// behavior in {@link #execute()}.
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
public abstract class WorkflowCommand extends HensuCommand {

    @Option(
            names = {"-d", "--working-dir"},
            description = "Working directory containing workflows/, prompts/, and rubrics/")
    protected Path workingDirPath;

    protected abstract void execute();

    @ConfigProperty(name = "hensu.workflow.file")
    private Optional<String> defaultWorkflowName;

    @ConfigProperty(name = "hensu.working.dir")
    private Optional<String> defaultWorkingDir;

    @Inject private KotlinScriptParser kotlinParser;

    @Inject private SubWorkflowLoader subWorkflowLoader;

    /// Sub-workflows loaded alongside the root by the most recent
    /// {@link #getWorkflow(String, List)} call, in declaration order and deduplicated.
    /// Empty when the root was loaded without `--with` or has no sub-workflow references.
    /// Subclasses that forward execution off-JVM (e.g. to the background daemon) must
    /// transport these alongside the root — the loader only registers them in the CLI's
    /// in-memory repository.
    protected List<Workflow> loadedSubWorkflows = List.of();

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
        return getWorkflow(workflowName, List.of());
    }

    /// Loads and parses the root workflow plus every sub-workflow declared via `--with`.
    ///
    /// Each `withName` is resolved through the same {@link WorkingDirectory} as the root
    /// (shared `workflows/`, `prompts/`, `rubrics/`). The compiled workflows are saved to
    /// the shared repository under the CLI tenant so `SubWorkflowNodeExecutor` can resolve
    /// them at runtime.
    ///
    /// @param workflowName root workflow name, may be null
    /// @param withNames sub-workflow names declared via `--with`, may be empty
    /// @return parsed root workflow, never null
    /// @throws UnsupportedWorkflowException if no root workflow name is configured
    protected Workflow getWorkflow(String workflowName, List<String> withNames)
            throws UnsupportedWorkflowException {
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

        Workflow root = kotlinParser.parse(workingDir, effectiveWorkflowName);
        loadedSubWorkflows = resolveSubWorkflows(workingDir, root, withNames);
        return root;
    }

    /// Hook for subclasses to override sub-workflow resolution behavior.
    ///
    /// @return loaded sub-workflows in declaration order; empty when none are required
    protected List<Workflow> resolveSubWorkflows(
            WorkingDirectory workingDir, Workflow root, List<String> withNames) {
        return subWorkflowLoader.resolveDeclared(workingDir, root, withNames);
    }

    /// Returns the effective working directory for workflow resolution.
    ///
    /// Resolution priority: CLI option `-d` > config property `hensu.working.dir` >
    /// current directory.
    ///
    /// @return working directory containing workflows/, prompts/, and rubrics/, never null
    protected WorkingDirectory getWorkingDirectory() {
        Path effectivePath =
                Objects.requireNonNullElseGet(
                        workingDirPath, () -> defaultWorkingDir.map(Path::of).orElse(Path.of(".")));
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
        return defaultWorkflowName.orElse(null);
    }
}
