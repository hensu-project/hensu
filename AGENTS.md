# AGENTS.md: Hensu™ Project Operational Manual

Single Source of Truth for all AI coding agents. You MUST read this before proposing changes or executing commands.

Guides live in [docs/](docs/) — read on demand (core/server developer guides, DSL reference, architecture, javadoc standards).

## Build

Standard Gradle wrapper (`./gradlew build`, `./gradlew test`, `./gradlew <module>:test --tests "FooTest"`, `./gradlew hensu-server:quarkusDev`).

Modules: `hensu-core`, `hensu-dsl`, `hensu-serialization`, `hensu-cli`, `hensu-server`, `hensu-langchain4j-adapter`.
Dependency flow: `cli → dsl → core`, `cli → serialization → core`, `server → serialization → core`, `langchain4j-adapter → core`.

## Architecture

Hensu is a modular AI workflow engine on Java 25 + Kotlin DSL. Core design principle: **zero external dependencies in `hensu-core`** — all AI provider integrations go through the `AgentProvider` interface, wired explicitly via `HensuFactory.builder().agentProviders(...)`. No classpath scanning, GraalVM-native-safe.

## Patterns & Conventions

1. **Builder pattern** for all domain models: `Workflow.builder().id(...).build()`
2. **Constructor injection** — no `@Autowired`, explicit dependency wiring
3. **Sealed interfaces** for results: `ExecutionResult` → `Completed | Paused | Rejected | Failure | Success`
4. **Template resolution**: `{variable}` syntax in prompts, resolved via `SimpleTemplateResolver`
5. **`@DslMarker`** on Kotlin builders to prevent scope leakage

## Key Architectural Rules

1. **HensuFactory pattern**: ALWAYS use `HensuFactory.builder()` — never construct core components directly.
2. **Client-side compilation**: CLI compiles Kotlin DSL → JSON; server receives pre-compiled JSON (no Kotlin compiler in native image).
3. **Build-then-push**: `hensu build` compiles to `{working-dir}/build/`; `hensu push` reads compiled JSON (no recompilation).
4. **Shared serialization**: CLI and server both use `hensu-serialization`; `WorkflowSerializer.createMapper()` is the single `ObjectMapper` factory.
5. **Server MCP-only**: server never executes bash locally, only MCP requests to external tools.
6. **Storage in core**: repository interfaces and in-memory defaults live in `hensu-core`. JDBC impls live in `hensu-server/persistence/` as plain classes (not CDI beans). `HensuEnvironmentProducer` conditionally wires JDBC vs in-memory. Server exposes core components via `@Produces @Singleton` — never instantiates directly.
7. **API separation**: `/api/v1/workflows` (definitions) and `/api/v1/executions` (runtime) are distinct resources.
8. **JWT authentication**: SmallRye JWT bearer auth. Tenant identity extracted from `tenant_id` claim via `RequestTenantResolver`. CLI sends `Authorization: Bearer <token>` via `--token` or `hensu.server.token` config. Dev/test mode disables auth (`hensu.tenant.default`). RSA keys live outside the repo (e.g. `~/.hensu/`).

## CLI

Verbs: `run | validate | visualize | build | push | pull | delete | list`. Use `-h` for args. `build` compiles `.kt` → JSON in `{working-dir}/build/`; `push` reads compiled JSON by workflow ID (not the `.kt`).

Example workflows: `working-dir/workflows/*.kt`.

## Environment Variables

Provider credentials loaded via `HensuFactory.loadCredentialsFromEnvironment()`: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GOOGLE_API_KEY`, `DEEPSEEK_API_KEY`, `OPENROUTER_API_KEY`, `AZURE_OPENAI_KEY`.

## Testing

- JUnit 5 + AssertJ + Mockito. Stub mode: `HENSU_STUB_ENABLED=true`. Core testable in isolation (no AI deps).
- **Integration tests** (`hensu-server`): extend `IntegrationTestBase`, run under `@QuarkusTest` with `@TestProfile(InMemoryTestProfile.class)`. The `inmem` profile disables PostgreSQL (no Docker). Base class provides `loadWorkflow`, `registerStub`, `pushAndExecute`, `pushAndExecuteWithMcp`, `resolveRubricPath`. Per-test cleanup clears `StubResponseRegistry` and deletes tenant data (execution states first, FK constraint).
- **Test handlers** (auto-discovered `@ApplicationScoped`): `TestActionHandler`, `TestReviewHandler`, `TestPauseHandler`, `TestValidatorHandler`.
- **Stub resolution order**: programmatic → `/stubs/{scenario}/{nodeId}.txt` → `/stubs/default/{nodeId}.txt` → echo fallback.
- **Repository tests** (`io.hensu.server.persistence`): plain JUnit 5 + Testcontainers PostgreSQL (no Quarkus). `JdbcRepositoryTestBase` starts container, runs Flyway, provides `DataSource`.
