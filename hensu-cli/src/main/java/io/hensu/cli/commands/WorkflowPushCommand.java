package io.hensu.cli.commands;

import io.hensu.dsl.WorkingDirectory;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// Pushes a compiled workflow JSON to the Hensu server.
///
/// Reads from `{working-dir}/build/{workflow-id}.json` — the output of `hensu build`.
/// Run `hensu build` first to compile the Kotlin DSL to JSON.
///
/// ### Usage
/// ```
/// hensu push my-workflow          # reads build/my-workflow.json
/// hensu push my-workflow -d /path/to/project
/// hensu push my-workflow --server http://prod:8080 --token "$TOKEN"
/// ```
///
/// ### Working Directory Resolution
/// Priority order: CLI option `-d` > config property `hensu.working.dir` > current directory.
///
/// @see WorkflowBuildCommand for compiling workflows
@Command(name = "push", description = "Push a compiled workflow to the server")
public class WorkflowPushCommand extends ServerCommand {

    @Parameters(index = "0", description = "Workflow ID (matches compiled JSON filename)")
    private String workflowId;

    @Option(
            names = {"-d", "--working-dir"},
            description = "Working directory containing the build/ output folder")
    private Path workingDirPath;

    @Inject
    @ConfigProperty(name = "hensu.working.dir")
    private Optional<String> defaultWorkingDir;

    @Override
    protected void execute() {
        Path buildDir = getWorkingDirectory().root().resolve(WorkflowBuildCommand.BUILD_DIR);
        Path jsonFile = buildDir.resolve(workflowId + ".json");

        if (!Files.exists(jsonFile)) {
            System.err.println("Compiled workflow not found: " + jsonFile);
            System.err.println("Run 'hensu build' first to compile the workflow.");
            return;
        }

        String json;
        try {
            json = Files.readString(jsonFile);
        } catch (IOException e) {
            System.err.println("Failed to read compiled workflow: " + e.getMessage());
            return;
        }

        System.out.println("Pushing " + workflowId + " to " + getServerUrl());

        HttpResponse<String> response = httpPost("/api/v1/workflows", json);

        if (response.statusCode() == 200 || response.statusCode() == 201) {
            String action = response.statusCode() == 201 ? "Created" : "Updated";
            System.out.println(action + ": " + workflowId);
        } else {
            printHttpError(response.statusCode(), response.body());
        }
    }

    /// Returns the effective working directory for locating compiled workflow JSON files.
    ///
    /// @return working directory, never null
    private WorkingDirectory getWorkingDirectory() {
        Path effectivePath =
                Objects.requireNonNullElseGet(
                        workingDirPath, () -> defaultWorkingDir.map(Path::of).orElse(Path.of(".")));
        return WorkingDirectory.of(effectivePath.toAbsolutePath());
    }
}
