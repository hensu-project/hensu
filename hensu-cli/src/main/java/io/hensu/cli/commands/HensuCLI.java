package io.hensu.cli.commands;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine.Command;

/// Main entry point for the Hensu CLI application.
///
/// ### Local workflow commands
/// - `run`         — Execute a workflow (daemon-aware; falls back to inline)
/// - `validate`    — Validate workflow syntax and detect unreachable nodes
/// - `visualize`   — Render workflow as ASCII text or Mermaid diagram
/// - `build`       — Compile Kotlin DSL to JSON (`{working-dir}/build/`)
///
/// ### Daemon commands (local engine, like Docker)
/// - `daemon`      — Start / stop / status the background daemon
/// - `ps`          — List workflow executions tracked by the daemon
/// - `attach`      — Stream output from a running execution
/// - `cancel`      — Cancel a running execution
///
/// ### Credentials commands
/// - `credentials` — Set, list, or unset API keys in `~/.hensu/credentials`
///
/// ### Server commands (remote hensu-server)
/// - `push`        — Push compiled workflow JSON to server
/// - `pull`        — Pull workflow definition from server
/// - `delete`      — Delete workflow from server
/// - `list`        — List all workflows on server
///
/// @see WorkflowRunCommand
/// @see DaemonCommand
/// @see CredentialsCommand
/// @see PsCommand
/// @see AttachCommand
/// @see CancelCommand
@TopCommand
@Command(
        name = "hensu",
        description = "Hensu Agentic Workflow Engine",
        subcommands = {
            WorkflowRunCommand.class,
            WorkflowValidateCommand.class,
            WorkflowVisualizeCommand.class,
            WorkflowBuildCommand.class,
            DaemonCommand.class,
            PsCommand.class,
            CredentialsCommand.class,
            AttachCommand.class,
            CancelCommand.class,
            WorkflowPushCommand.class,
            WorkflowPullCommand.class,
            WorkflowDeleteCommand.class,
            WorkflowListCommand.class
        })
public class HensuCLI {}
