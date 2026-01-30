package io.hensu.cli.commands;

import io.hensu.cli.exception.UnsupportedWorkflowException;
import io.hensu.core.workflow.Workflow;
import io.hensu.dsl.WorkingDirectory;
import io.hensu.dsl.parsers.KotlinScriptParser;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import picocli.CommandLine.Option;

public abstract class WorkflowCommand implements Runnable {

    private static final String[] BANNER = {
        "",
        "  _",
        " | |__    ___  _ __   ___  _   _",
        " | '_ \\  / _ \\| '_ \\ / __|| | | |",
        " | | | ||  __/| | | |\\__ \\| |_| |",
        " |_| |_| \\___||_| |_||___/ \\__,_|",
        "",
        " Agentic Workflow Engine",
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
    private Optional<String> defaultWorkflowName;

    @Inject
    @ConfigProperty(name = "hensu.working.dir")
    private Optional<String> defaultWorkingDir;

    @Inject private KotlinScriptParser kotlinParser;

    /// Get workflow by name from the working directory structure.
    ///
    /// @param workflowName Workflow name (with or without .kt extension)
    /// @return Parsed workflow
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

    /// Get the effective working directory. Priority: CLI option > config property > current
    /// directory
    protected WorkingDirectory getWorkingDirectory() {
        Path effectivePath =
                Objects.requireNonNullElseGet(
                        workingDirPath, () -> defaultWorkingDir.map(Path::of).orElse(Path.of(".")));
        return WorkingDirectory.of(effectivePath.toAbsolutePath());
    }

    protected String resolveWorkflowName(String workflowName) {
        if (workflowName != null && !workflowName.isBlank()) {
            return workflowName;
        }
        return defaultWorkflowName.orElse(null);
    }
}
