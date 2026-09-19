# AGENTS.md: Hensu™ Project Operational Manual

Single Source of Truth for all AI coding agents. You MUST read this before proposing changes or executing commands.

Guides live in [docs/](docs/) — read on demand (core/server developer guides, DSL reference, command catalog, architecture, javadoc standards).

## Build

Standard Gradle wrapper (`./gradlew build`, `./gradlew test`, `./gradlew <module>:test --tests "FooTest"`, `./gradlew hensu-server:quarkusDev`).

On Linux, the sandboxed command execution tests (`SandboxContainmentTest`) need bubblewrap to be
allowed to create user namespaces, which Ubuntu 24.04 denies by default. They skip until the
project's AppArmor profile is installed — the same file CI loads:

```bash
sudo apt-get install -y bubblewrap
sudo install -m 644 tools/apparmor/bwrap /etc/apparmor.d/bwrap
sudo apparmor_parser -r /etc/apparmor.d/bwrap
```

Modules: `hensu-core`, `hensu-dsl`, `hensu-serialization`, `hensu-mcp`, `hensu-langchain4j-adapter`, `hensu-cli`, `hensu-server`.

Dependency flow — `hensu-core` is the root and depends on nothing; every other module points at it:

- `dsl → core`, `serialization → core`, `mcp → core`, `langchain4j-adapter → core`
- `cli → dsl, serialization, mcp, langchain4j-adapter`
- `server → core, serialization, mcp, langchain4j-adapter`

`cli` and `server` are the **two execution runtimes** and share everything beneath them, `hensu-mcp` included — MCP is not a server-only concern.

## Architecture

Hensu is a modular AI workflow engine on Java 25 + Kotlin DSL. It ships **two execution runtimes** over one engine: `hensu-server` (GraalVM native image, multi-tenant, executes nothing locally) and `hensu-cli` (JVM, single operator, executes locally under an OS sandbox). `hensu-core` is the engine both embed — a library, not a runtime. Which runtime a change targets decides what is safe in it; see Rule 5.

Core design principle: **zero external dependencies in `hensu-core`** — all AI provider integrations go through the `AgentProvider` interface, wired explicitly via `HensuFactory.builder().agentProviders(...)`. No classpath scanning, GraalVM-native-safe.

## Patterns & Conventions

1. **Builder pattern** for all domain models: `Workflow.builder().id(...).build()`
2. **Constructor injection** — no `@Autowired`, explicit dependency wiring
3. **Sealed interfaces** for results: `ExecutionResult` → `Completed | Paused | Rejected | Failure | Success`
4. **Template resolution**: `{variable}` syntax in prompts, resolved via `SimpleTemplateResolver`
5. **`@DslMarker`** on Kotlin builders to prevent scope leakage
6. **Engine variables** (`score`, `approved`, `recommendation`): engine-managed — `writes()` rejects them at build time and again at load time, and declaring one in `state{}` is redundant. Surfaced to the next node by the `EngineVariablePromptEnricher` injectors (`FeedbackContextInjector` appends prior feedback as a `### Previous Feedback` section). A backtracking `revise` arm preserves them; a plain forward `goto` clears them unless marked `withFeedback`.
7. **Agent tool loop**: agents implementing `ToolCapable` open a call-scoped `ToolSession` and execute tools natively via `ToolLoopRunner` — the sealed `AgentResponse` hierarchy (`TextResponse`/`ToolRequest`/`Error`) controls flow. Budget enforced by `AgentConfig.maxToolCalls` (default 10, counting executed calls). Tools reach the loop through the `ToolProvider`/`ToolRouter` seam, never through `ActionExecutor`, and are invoked via the context's `ToolInvoker`; every request and outcome is reported to the `ExecutionListener` (`onToolCall`/`onToolResult`), including hallucinated names and budget rejections. Outcomes are typed: every layer maps onto the single `ToolCallStatus` vocabulary rather than inventing its own error strings, and a provider that fails while reporting its catalog is skipped rather than propagated — a broken tool source fails a node, never the execution.

## Key Architectural Rules

1. **HensuFactory pattern**: ALWAYS use `HensuFactory.builder()` — never construct core components directly.
2. **Client-side compilation**: the Kotlin compiler only ever runs in the CLI; the server receives pre-compiled JSON (no Kotlin compiler in a native image). This is about *compilation*, not execution — `hensu run` compiles in-process and then executes the workflow locally, with no server involved. `push` / `pull` / `list` / `delete` are the subset of the CLI's surface that talks to a server at all.
3. **Build-then-push**: `hensu build` compiles to `{working-dir}/build/`; `hensu push` reads compiled JSON (no recompilation).
4. **Shared serialization**: CLI and server both use `hensu-serialization`; `WorkflowSerializer.createMapper()` is the single `ObjectMapper` factory.
5. **Two runtimes, one boundary rule.** The server never executes anything locally — side effects leave as MCP requests to tenant-owned servers. The CLI does execute locally, through the `commands.yaml` allowlist (`CommandRegistry`) under an OS sandbox (`CommandRunner` + `SandboxLauncher`, bubblewrap or Seatbelt). The rule governing both: **on a path where the agent authors the code being executed, the sandbox is the security boundary and the catalog governs grants, not reachability** — wherever an entry's binary executes *content* the agent can write — an interpreter running a script, a build tool running a build file, a migration runner running migrations — the catalog has already granted arbitrary code execution inside the sandbox, so an `exec:` entry buys no containment there that a `rung:` entry does not. Unattended software development is the sharpest instance of this, not its scope. Everywhere else the catalog is a genuine allowlist. Exactly one code site may build a `/bin/sh` command line (`CommandRunner.bind`); `NoShellOnTheAgentPathTest` fails the build on a second. Do not add a shell path, and do not relax a sandbox policy to make a command work — widen the declared `write:` / `cache:` / `network:` where an operator can see it, or say why the entry should not exist. The CLI's agent-facing surface is three sources, not one: catalog commands, `mcp.yaml` servers contained once at launch, and built-in file tools that run **in-process, inside no sandbox at all** — their containment is `PathGuard`, so a change to file-tool path handling is a change to a security boundary and carries the same burden as touching a sandbox profile. Those file tools live in `hensu-core` because reading the input a run was told to act on is an engine capability, and the CLI is the only runtime that wires them: `NoLocalFileToolsOnTheServerTest` fails the build if a server source so much as names the package, since a provider rooted on a multi-tenant host would write where no tenant can see it. **Human presence is a property of the runtime, never of the engine**: the CLI derives one boolean from `--interactive` / `--unattended`, and exactly one decorator (`ApprovalToolProvider`, wrapped in the CLI producer) consults `ToolApprovalGate` with it — never a check inside a provider. An unattended run **refuses rather than escalates**, and the refusal reaches the graph as a record on `_capability_gaps` that an ordinary condition routes on (Rule 9). Both gap keys are engine-owned; declaring either is a build error at every site that can declare one — a node's `writes`, a parallel branch's `yields`, a sub-workflow node's `writes` — and the load-time validator refuses them again for a workflow that never passed through the DSL. A tool can also be missing because its *source* never offered it, which is not a refused call and leaves no gap: the CLI reports those reasons in a **Tool sources** section beside the gaps. That channel exists because the CLI ships `quarkus.log.console.level=OFF` with no file log, so anything reported through `logger.*` reaches nobody — operator-facing text goes to the run's output or to `ToolSourceNotices`, never to a logger. See `docs/unified-architecture.md` Decision 4 and `docs/cli-tool-execution-security-model.md`.
6. **Storage in core**: repository interfaces and in-memory defaults live in `hensu-core`. JDBC impls live in `hensu-server/persistence/` as plain classes (not CDI beans). `HensuEnvironmentProducer` conditionally wires JDBC vs in-memory. Server exposes core components via `@Produces @Singleton` — never instantiates directly.
7. **API separation**: `/api/v1/workflows` (definitions) and `/api/v1/executions` (runtime) are distinct resources.
8. **JWT authentication (server only)**: SmallRye JWT bearer auth guards the server's HTTP surface. Tenant identity extracted from `tenant_id` claim via `RequestTenantResolver`. The CLI sends `Authorization: Bearer <token>` via `--token` or `hensu.server.token` config **when it talks to a server** — a local `hensu run` authenticates nothing, because there is no boundary to cross. JWT is required in every server profile **except `inmem`** (integration tests), which disables auth and uses `hensu.tenant.default`. RSA keys live outside the repo (e.g. `~/.hensu/`).
9. **Nodes do work; transitions route.** Control-flow capabilities (iteration, exit conditions, budgets, feedback) are expressed in the `TransitionRule` sealed hierarchy — never as node types, node flags, or executor forks on node configuration. A request shaped like "this node needs to loop/branch/retry/converge" is answered with a new or extended transition rule wired through `requiredRoutingVars()`. `LoopNode` and the plan subsystem were removed for violating this. Do not reintroduce the pattern.
10. **A human verdict is not an engine variable.** Engine variables record what the *agent* said about its own output; a reviewer's decision is a different claim and travels on its own channel — a `ReviewVerdict` set on `HensuState` by `ReviewPostProcessor`, which writes no engine variable at all. `ApprovalTransition` gives the verdict precedence over `approved` when routing. This keeps `approved` to two writers with one meaning (output extraction, consensus) and makes "the reviewer decides" a property of the rule that reads the values rather than of the order in which processors run. Do not resolve a precedence question by having one processor overwrite another's variable.

## CLI

Use `-h` for args. The verbs split by whether a server is involved:

- **Local, no server** — `run`, `validate`, `visualize`, `build`, `daemon` (`start|stop|status`), `ps`, `attach`, `cancel`, `credentials` (`set|list|unset`)
- **Talks to a server** — `push`, `pull`, `delete`, `list`

`build` compiles `.kt` → JSON in `{working-dir}/build/`; `push` reads compiled JSON by workflow ID (not the `.kt`). `run` compiles in-process and executes here — it is the CLI acting as a runtime, not as a client.

Example workflows: `working-dir/workflows/*.kt`.

## Environment Variables

Provider credentials loaded via `HensuFactory.loadCredentialsFromEnvironment()`: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GOOGLE_API_KEY`, `DEEPSEEK_API_KEY`, `OPENROUTER_API_KEY`, `AZURE_OPENAI_KEY`.

## Testing

- JUnit 5 + AssertJ + Mockito. Stub mode: `HENSU_STUB_ENABLED=true`. Core testable in isolation (no AI deps).
- **Integration tests** (`hensu-server`): extend `IntegrationTestBase`, run under `@QuarkusTest` with `@TestProfile(InMemoryTestProfile.class)`. The `inmem` profile disables PostgreSQL (no Docker). Base class provides `loadWorkflow`, `registerStub`, `pushAndExecute`, `pushAndExecuteWithMcp`, `resolveRubricPath`. Per-test cleanup clears `StubResponseRegistry` and deletes tenant data (execution states first, FK constraint).
- **Test handlers** (auto-discovered `@ApplicationScoped`): `TestActionHandler`, `TestReviewHandler`, `TestPauseHandler`, `TestValidatorHandler`.
- **Stub resolution order**: programmatic → `/stubs/{scenario}/{nodeId}.txt` → `/stubs/default/{nodeId}.txt` → echo fallback.
- **Repository tests** (`io.hensu.server.persistence`): plain JUnit 5 + Testcontainers PostgreSQL (no Quarkus). `JdbcRepositoryTestBase` starts container, runs Flyway, provides `DataSource`.
