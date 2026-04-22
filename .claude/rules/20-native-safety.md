# 20-native-safety.md

# GraalVM Native Image — Always-On Invariants

The `hensu-server` binary is a GraalVM native image built via Quarkus. These invariants apply to **every** code change in a server-reachable module. Violating them silently breaks the native build.

## Invariants

1. **No reflection** — `Class.forName`, `Method.invoke`, `Constructor.newInstance` are forbidden outside explicitly registered entries in `reflect-config.json`.
2. **No classpath scanning, no dynamic proxies, no runtime bytecode generation.**
3. **No `ThreadLocal`** — use `ScopedValue` (see `10-java-standards.md` §Security).
4. **No reflection-based JSON binding in `hensu-core`** — tree-model only (`ObjectMapper.readTree` / `writeValueAsString`).
5. **No dynamic class loading inside `@Produces`** — producers must instantiate concrete types.
6. **Do not override versions managed by the Quarkus BOM** — mismatches cause subtle native failures.

Safe within Quarkus-managed code (build-time processed, no action needed): `@Inject`, `@Produces`, `@ConfigProperty`, JAX-RS annotations, Jackson `@JsonProperty` on DTOs, Mutiny `Uni`/`Multi`, `ServiceLoader` via `META-INF/services`.

## Procedural guidance — loaded on demand

When adding a dependency, writing a new CDI producer, authoring `reflect-config.json`, or verifying a native build, invoke the **`native-image-check`** skill. It carries the decision ladder, commands, examples, and background reading — no need to keep them in session context.
