package io.hensu.cli.commands;

import io.hensu.cli.exception.UnsupportedWorkflowException;
import io.hensu.core.workflow.Workflow;
import io.hensu.dsl.WorkingDirectory;
import io.hensu.serialization.WorkflowSerializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/// Compiles a Kotlin DSL workflow to JSON and writes to the build directory.
///
/// The compiled JSON is stored in `{working-dir}/build/{workflow-id}.json`
/// and can be pushed to the server via `hensu push`.
///
/// ### Usage
/// ```
/// hensu build my-workflow.kt
/// hensu build my-workflow.kt -d /path/to/working-dir
/// ```
///
/// @see WorkflowPushCommand for pushing compiled workflows to the server
@Command(name = "build", description = "Compile a Kotlin DSL workflow to JSON")
public class WorkflowBuildCommand extends WorkflowCommand {

    static final String BUILD_DIR = "build";

    @Parameters(index = "0", description = "Workflow name or path", arity = "0..1")
    private String workflowName;

    @Override
    protected void execute() {
        try {
            Workflow workflow = getWorkflow(workflowName);
            String json = WorkflowSerializer.toJson(workflow);

            Path buildDir = getWorkingDirectory().root().resolve(BUILD_DIR);
            Files.createDirectories(buildDir);

            Path outputFile = buildDir.resolve(workflow.getId() + ".json");
            Files.writeString(outputFile, json);

            System.out.println("Compiled: " + workflow.getId() + " v" + workflow.getVersion());
            System.out.println("Output:   " + outputFile);
        } catch (UnsupportedWorkflowException e) {
            System.err.println("Build failed: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
        }
    }

    /// Sub-workflows referenced by the root are built and pushed independently;
    /// the server resolves them at runtime. Local `--with` resolution is not required.
    @Override
    protected List<Workflow> resolveSubWorkflows(
            WorkingDirectory workingDir, Workflow root, List<String> withNames) {
        return List.of();
    }
}
