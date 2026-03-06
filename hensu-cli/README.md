# Hensu™ CLI

Local workflow engine for Hensu — executes, validates, and manages AI workflows on your machine,
then pushes them to a remote `hensu-server` when ready.

Acts like a local Docker Engine: a background **daemon** keeps the JVM and Kotlin compiler warm so
successive `hensu run` calls start in milliseconds. Detach from a running workflow with `Ctrl+C`
and re-attach at any time without interrupting execution.

## Table of Contents

- [Installation](#installation)
- [Usage](#usage)
- [Local Workflow Commands](#local-workflow-commands)
  - [`hensu run`](#hensu-run)
  - [`hensu validate`](#hensu-validate)
  - [`hensu visualize`](#hensu-visualize)
  - [`hensu build`](#hensu-build)
- [Daemon Commands](#daemon-commands)
  - [`hensu daemon`](#hensu-daemon)
  - [`hensu ps`](#hensu-ps)
  - [`hensu attach`](#hensu-attach)
  - [`hensu cancel`](#hensu-cancel)
  - [Detach (Ctrl+C)](#detach-ctrlc)
- [Credentials Commands](#credentials-commands)
  - [`hensu credentials set`](#hensu-credentials-set)
  - [`hensu credentials list`](#hensu-credentials-list)
  - [`hensu credentials unset`](#hensu-credentials-unset)
- [Server Commands](#server-commands)
  - [`hensu push`](#hensu-push)
  - [`hensu pull`](#hensu-pull)
  - [`hensu delete`](#hensu-delete)
  - [`hensu list`](#hensu-list)
- [Typical Workflows](#typical-workflows)
- [Project Layout](#project-layout)
- [Configuration](#configuration)
- [Development](#development)
- [Testing — Stub Agent System](#testing--stub-agent-system)
- [Dependencies](#dependencies)

## Installation

**Requires Java 25+.**

```bash
# Install latest release (recommended)
curl -sSL https://github.com/hensu-project/hensu/releases/latest/download/install.sh | bash

# Custom prefix
curl -sSL https://github.com/hensu-project/hensu/releases/latest/download/install.sh | bash -s -- --prefix /usr/local

# Skip service installation (systemd on Linux, launchd on macOS)
curl -sSL https://github.com/hensu-project/hensu/releases/latest/download/install.sh | bash -s -- --no-service

# Update to latest release
curl -sSL https://github.com/hensu-project/hensu/releases/latest/download/update.sh | bash

# Pin to a specific version
curl -sSL https://github.com/hensu-project/hensu/releases/latest/download/update.sh | bash -s -- --version cli/v0.9.0-beta.1

# Uninstall
bash <(curl -sSL https://github.com/hensu-project/hensu/releases/latest/download/remove.sh)
bash <(curl -sSL https://github.com/hensu-project/hensu/releases/latest/download/remove.sh) --purge   # also removes ~/.hensu/ data
```

All releases and assets: [github.com/hensu-project/hensu/releases](https://github.com/hensu-project/hensu/releases)

> **From source:** clone the repo and run `bash hensu-cli/scripts/install.sh`. See `--help` for all options.

## Usage

```
usage: hensu <command> [<args>]

Local workflow commands
   run        Execute a workflow (daemon-aware; inline fallback)
   validate   Validate workflow syntax and detect unreachable nodes
   visualize  Render workflow graph as text or Mermaid diagram
   build      Compile Kotlin DSL to JSON  →  {working-dir}/build/

Daemon commands  (local execution engine, analogous to Docker Engine)
   daemon     Manage the background daemon  (start | stop | status)
   ps         List workflow executions tracked by the daemon
   attach     Stream output from a running or completed execution
   cancel     Cancel a running execution

Credentials commands
   credentials  set, list, or unset API keys in ~/.hensu/credentials

Server commands  (remote hensu-server)
   push       Push compiled workflow JSON to the server
   pull       Pull a workflow definition from the server
   delete     Delete a workflow from the server
   list       List all workflows on the server
```

---

## Local Workflow Commands

All local workflow commands accept:

```
-d, --working-dir <path>   Working directory containing workflows/, prompts/, and rubrics/
                           (default: . | override: hensu.working.dir in application.properties)
```

### `hensu run`

Execute a workflow. Automatically delegates to the daemon when running; falls back to inline
execution when the daemon is not available.

```
hensu run [<workflow-name>] [-d <working-dir>]
          [-v] [-i] [--no-color] [--no-daemon] [-c <context>]

options:
  <workflow-name>          Workflow name from workflows/ (default: hensu.workflow.file property)
  -d, --working-dir        Working directory
  -c, --context <value>    Context as a JSON string  '{"key":"value"}'  or path to a JSON/YAML file
  -v, --verbose            Show agent inputs and outputs
  -i, --interactive        Enable interactive human review mode with manual backtracking
      --no-color           Disable ANSI colored output
      --no-daemon          Force inline execution even if the daemon is running
```

**Ctrl+C behavior:** pressing Ctrl+C *detaches* the client — execution keeps running in the daemon.
The terminal prints the execution ID and re-attach instructions.

### `hensu validate`

Parse the workflow and perform static analysis: syntax errors, missing node references, and
unreachable nodes.

```
hensu validate [<workflow-name>] [-d <working-dir>]
```

### `hensu visualize`

Render the workflow graph to the terminal.

```
hensu visualize [<workflow-name>] [-d <working-dir>] [--format <fmt>]

options:
  --format <text|mermaid>  Output format  (default: text)
```

### `hensu build`

Compile the Kotlin DSL to JSON. Output is written to `{working-dir}/build/{workflow-id}.json`
and is required by `hensu push`.

```
hensu build [<workflow-name>] [-d <working-dir>]
```

---

## Daemon Commands

The daemon is an optional background process that eliminates JVM cold-start on every `hensu run`.
It mirrors the role `hensu-server` plays for remote deployments.

### `hensu daemon`

```
hensu daemon <subcommand>

subcommands:
  start    Start the daemon in the background; polls until the socket appears
  stop     Graceful shutdown (finishes in-flight executions, then exits)
  status   Show socket path, running execution count, and total tracked executions
```

### `hensu ps`

List all executions tracked by the daemon (running + recently completed).

```
hensu ps
```

Example output:

```
         ID                                    WORKFLOW                  STATUS        NODE              ELAPSED
  ——————————————————————————————————————————————————————————————————————————————————————————————————————————————
  ●  3f2a1c9e-…-4b1a                          my-workflow               RUNNING       summarize         12s
  ●  7b9d4efa-…-22cc                          other-workflow            COMPLETED     —                 1m 5s
```

### `hensu attach`

Replay buffered output from the ring buffer, then stream live output until the execution
completes or you press Ctrl+C (which detaches without stopping the execution).

```
hensu attach <exec-id>

arguments:
  <exec-id>   Execution ID from `hensu ps`
```

### `hensu cancel`

Send a cancellation signal to a running execution in the daemon.

```
hensu cancel <exec-id>

arguments:
  <exec-id>   Execution ID to cancel  (from `hensu ps`)
```

### Detach (Ctrl+C)

There is no `hensu detach` command. Detach is triggered by pressing **Ctrl+C** inside
`hensu run` or `hensu attach`. It sends a `detach` frame to the daemon — the execution
keeps running; only the client terminal disconnects.

```
Ctrl+C during `hensu run`     →  detaches; prints exec ID and re-attach hint
Ctrl+C during `hensu attach`  →  same
```

---

## Credentials Commands

API keys are stored in `~/.hensu/credentials` — one `KEY=VALUE` per line, `#` for
comments. The file is read at startup by both the daemon and direct CLI runs, so it
works regardless of how the process was launched. The installer creates it with
commented-out examples on first install.

```
# ~/.hensu/credentials
ANTHROPIC_API_KEY=sk-ant-...
GOOGLE_API_KEY=AIza...
OPENAI_API_KEY=sk-...
```

You can also export the equivalent environment variables (`ANTHROPIC_API_KEY`, etc.)
instead of using the file.

The commands below are the recommended way to manage the file: values are read via a
masked prompt so they never enter shell history, and all writes enforce `0600`
permissions. When the daemon is running at the time of a write, a restart hint is
printed — credentials are loaded once at daemon startup.

### `hensu credentials set`

Set or update a single credential. The value is read via a masked prompt so it never
appears in shell history. Use `--stdin` in non-interactive environments.

```
hensu credentials set <KEY> [--stdin]

arguments:
  <KEY>      Credential key name, e.g. ANTHROPIC_API_KEY

options:
  --stdin    Read value from stdin instead of an interactive prompt
```

Example:

```
$ hensu credentials set ANTHROPIC_API_KEY
Enter value for ANTHROPIC_API_KEY: *******
✓ ANTHROPIC_API_KEY saved to ~/.hensu/credentials

# CI / scripting
$ echo "$MY_API_KEY" | hensu credentials set ANTHROPIC_API_KEY --stdin
```

### `hensu credentials list`

Show the names of all configured credentials. Values are always masked.

```
hensu credentials list
```

Example output:

```
Configured credentials  (~/.hensu/credentials)
  ANTHROPIC_API_KEY   ***
  GOOGLE_API_KEY      ***
```

### `hensu credentials unset`

Remove a single credential from the file. Comment lines and all other entries are
preserved.

```
hensu credentials unset <KEY>

arguments:
  <KEY>   Credential key to remove
```

---

## Server Commands

All server commands accept:

```
--server <url>    Server base URL  (default: http://localhost:8080 | override: hensu.server.url)
--token  <jwt>    JWT bearer token  (override: hensu.server.token)
```

### `hensu push`

Push a compiled workflow JSON to the server. Run `hensu build` first.

```
hensu push <workflow-id> [-d <working-dir>] [--server <url>] [--token <jwt>]

arguments:
  <workflow-id>            Workflow ID — must match a file in {working-dir}/build/
  -d, --working-dir        Directory that contains the build/ output folder
```

### `hensu pull`

Fetch a workflow definition from the server and print it to stdout.

```
hensu pull <workflow-id> [--server <url>] [--token <jwt>]
```

### `hensu delete`

Delete a workflow from the server.

```
hensu delete <workflow-id> [--server <url>] [--token <jwt>]
```

### `hensu list`

List all workflows registered on the server for the current tenant.

```
hensu list [--server <url>] [--token <jwt>]
```

Output:

```
ID                              VERSION
---------------------------------------------
my-workflow                     1
other-workflow                  3
```

---

## Typical Workflows

### Run once (no daemon)

```bash
hensu run my-workflow -d ./my-project
```

### Run with daemon (fast cold-start)

```bash
hensu daemon start
hensu run my-workflow -d ./my-project          # delegates to daemon automatically
hensu ps                                       # see running executions
hensu attach <exec-id>                         # reconnect after Ctrl+C
```

### Build and push to server

```bash
# 1. Compile DSL to JSON on your machine (server cannot run the Kotlin compiler)
hensu build my-workflow -d ./my-project

# 2. Push compiled JSON to server
hensu push my-workflow -d ./my-project --server http://localhost:8080
```

### Auto-start daemon on login

**Linux (systemd):**

```bash
# Enable socket activation — daemon starts on-demand when first hensu run is called
systemctl --user enable --now hensu-daemon.socket
```

**macOS (launchd):**

```bash
# Load the agent and mark it to start automatically on login
launchctl load -w ~/Library/LaunchAgents/io.hensu.daemon.plist
```

---

## Project Layout

    A typical Hensu working directory (`-d <working-dir>`):
    
    ```
    working-dir/
    +— workflows/                          # Kotlin DSL workflow definitions
    +— stubs/                              # Agent stub responses for local testing
    +— prompts/                            # Input prompt files
    +— rubrics/                            # Evaluation rubric definitions
    │   +— templates/
    +— build/                              # Output of `hensu build` (JSON artifacts)
    +— commands.yaml                       # CLI command shortcuts
    ```

## Configuration

### API Credentials

See [Credentials Commands](#credentials-commands) above.

### Application Properties

`hensu-cli/src/main/resources/application.properties`:

```properties
# Default server URL for push / pull / delete / list
hensu.server.url=http://localhost:8080

# Optional JWT token for server commands
hensu.server.token=

# Default working directory (overridden by -d on any command)
hensu.working.dir=

# Default workflow file (overridden by positional argument on run/validate/etc.)
hensu.workflow.file=
```

All properties can be overridden at runtime with the corresponding CLI option.

## Development

```bash
# Build
./gradlew hensu-cli:build

# Run in Quarkus dev mode (hot reload)
./gradlew hensu-cli:quarkusDev
```

## Testing — Stub Agent System

The stub agent system runs full workflow executions without consuming API tokens. When enabled,
`StubAgentProvider` (priority 1000) intercepts all agent calls and returns responses from
`StubResponseRegistry`. Place `.txt` files under `stubs/default/{nodeId}.txt` in your working
directory to supply per-node mock responses.

**Enable stub mode** (four equivalent ways):

```bash
# 1. Environment variable (recommended for CLI use)
export HENSU_STUB_ENABLED=true
hensu run my-workflow -d ./my-project

# 2. System property
java -Dhensu.stub.enabled=true -jar ~/.hensu/lib/hensu.jar run my-workflow

# 3. Application property (src/main/resources/application.properties)
# hensu.stub.enabled=true

# 4. Credentials map (per-execution override in Java code)
# credentials.put("HENSU_STUB_ENABLED", "true");
```

> See [Stub Agent System](../docs/developer-guide-core.md#stub-agent-system) in the Core
> Developer Guide for response resolution order, programmatic registration, and scenario-based
> stubs.

## Dependencies

| Module / Library             | Role                                      |
|------------------------------|-------------------------------------------|
| `hensu-core`                 | Workflow engine, execution, agent API     |
| `hensu-serialization`        | Jackson-based JSON for workflows          |
| `hensu-langchain4j-adapter`  | LLM provider integration (Anthropic, etc) |
| `quarkus-picocli`            | Command-line framework                    |
| `quarkus-arc`                | CDI container                             |
| `kotlin-compiler-embeddable` | Client-side Kotlin DSL compilation        |
