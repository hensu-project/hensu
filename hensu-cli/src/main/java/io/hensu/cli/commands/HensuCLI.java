package io.hensu.cli.commands;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine.Command;

/// Main entry point for the Hensu CLI application.
///
/// Registers all available subcommands for workflow management:
/// - `run` - Execute a workflow with optional verbose/interactive modes
/// - `validate` - Validate workflow syntax and detect unreachable nodes
/// - `visualize` - Render workflow as ASCII text or Mermaid diagram
/// - `build` - Compile Kotlin DSL to JSON (`{working-dir}/build/`)
/// - `push` - Push compiled workflow JSON to server
/// - `pull` - Pull workflow definition from server
/// - `delete` - Delete workflow from server
/// - `list` - List all workflows on server
///
/// @see WorkflowRunCommand
/// @see WorkflowValidateCommand
/// @see WorkflowVisualizeCommand
/// @see WorkflowBuildCommand
/// @see WorkflowPushCommand
/// @see WorkflowPullCommand
/// @see WorkflowDeleteCommand
/// @see WorkflowListCommand
@TopCommand
@Command(
        name = "hensu",
        description = "Hensu Agentic Workflow Engine",
        subcommands = {
            WorkflowRunCommand.class,
            WorkflowValidateCommand.class,
            WorkflowVisualizeCommand.class,
            WorkflowBuildCommand.class,
            WorkflowPushCommand.class,
            WorkflowPullCommand.class,
            WorkflowDeleteCommand.class,
            WorkflowListCommand.class
        })
public class HensuCLI {}
