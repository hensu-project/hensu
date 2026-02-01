package io.hensu.cli.commands;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine.Command;

/// Main entry point for the Hensu CLI application.
///
/// Registers all available subcommands for workflow management:
/// - `run` - Execute a workflow with optional verbose/interactive modes
/// - `validate` - Validate workflow syntax and detect unreachable nodes
/// - `visualize` - Render workflow as ASCII text or Mermaid diagram
///
/// @see WorkflowRunCommand
/// @see WorkflowValidateCommand
/// @see WorkflowVisualizeCommand
@TopCommand
@Command(
        name = "hensu",
        description = "Hensu Agentic Workflow Engine",
        subcommands = {
            WorkflowRunCommand.class,
            WorkflowValidateCommand.class,
            WorkflowVisualizeCommand.class
        })
public class HensuCLI {}
