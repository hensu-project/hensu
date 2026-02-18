# Hensu™ CLI

Quarkus-based CLI tool for compiling, executing, and managing Hensu AI workflows.

## Overview

The `hensu-cli` module provides:

- **Kotlin DSL Compiler** - Compiles `.kt` workflow definitions to JSON
- **Local Execution** - Runs workflows locally with full agent support
- **Build & Push** - Compile-then-push workflow to build JSON artifacts for the server
- **Server Management** - Push, pull, delete, list workflows on remote server

## Quick Start

```bash
# Build the CLI
./gradlew hensu-cli:build

# Run in dev mode
./gradlew hensu-cli:quarkusDev
```

## Commands

### Local Commands

```bash
# Execute a workflow locally
hensu run workflow.kt -d working-dir
hensu run workflow.kt -d working-dir -v          # Verbose output

# Validate workflow syntax
hensu validate workflow.kt -d working-dir

# Visualize workflow graph
hensu visualize workflow.kt -d working-dir
hensu visualize workflow.kt -d working-dir --format mermaid
```

### Build

Compiles Kotlin DSL to JSON and writes to `{working-dir}/build/{workflow-id}.json`:

```bash
hensu build workflow.kt -d working-dir
```

### Server Commands

All server commands support `--server` and `--token` options:

```bash
# Push compiled workflow to server (requires prior `hensu build`)
hensu push <workflow-id>
hensu push <workflow-id> --server http://prod:8080 --token "$TOKEN"

# Pull workflow definition from server
hensu pull <workflow-id>

# Delete workflow from server
hensu delete <workflow-id>

# List all workflows on server
hensu list
```

### Build-Then-Push Workflow

```bash
# 1. Compile DSL to JSON
hensu build my-workflow.kt -d working-dir

# 2. Push compiled JSON to server
hensu push my-workflow --server http://localhost:8080
```

The `push` command reads from `{working-dir}/build/{workflow-id}.json` — it does not recompile the DSL.

## Configuration

### application.properties

```properties
# Default server URL for push/pull/delete/list commands
hensu.server.url=http://localhost:8080
```

Override at runtime with `--server` option on any server command.

## Dependencies

- **hensu-core** - Core workflow engine + Kotlin DSL
- **hensu-serialization** - Jackson-based JSON serialization
- **hensu-langchain4j-adapter** - LLM provider integration
- **Quarkus PicoCLI** - Command-line framework
