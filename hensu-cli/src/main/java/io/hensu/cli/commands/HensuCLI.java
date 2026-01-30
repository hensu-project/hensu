package io.hensu.cli.commands;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine.Command;

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
