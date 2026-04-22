---
name: native-image-check
description: Use when adding a new dependency to hensu-server, writing a new CDI `@Produces` method, introducing reflection, dynamic proxies, classpath scanning, or `ServiceLoader`, or preparing a release that needs native-image verification. Covers the Quarkus-extension-first decision, `reflect-config.json` authoring, BOM version pinning, CDI producer patterns, Mutiny/MCP safety notes, and the native build/verify commands. Triggers on phrases like "add dependency", "native build", "graalvm", "quarkus extension", "reflect-config", "Class.forName", or any edit to `build.gradle.kts` that adds an `implementation(...)` line in a server-side module.
---

# GraalVM Native Image Safety Skill

Background reading: [hensu-core Developer Guide — GraalVM Native Image Constraints](../../../docs/developer-guide-core.md#graalvm-native-image-constraints).

The always-on invariants (no reflection, no `ThreadLocal`, no classpath scanning, etc.) live in `.claude/rules/20-native-safety.md`. This skill covers the **procedural** guidance — the how and when.

---

## How Quarkus changes the picture

Quarkus performs heavy build-time processing that relaxes some raw GraalVM constraints:

| Feature                            | Raw GraalVM                | With Quarkus                                               |
|------------------------------------|----------------------------|------------------------------------------------------------|
| CDI injection (`@Inject`)          | Requires reflection config | Works — Quarkus resolves beans at build time (ArC)         |
| `@ConfigProperty`                  | Requires reflection config | Works — processed at build time                            |
| JAX-RS resources (`@Path`, `@GET`) | Requires reflection config | Works — REST layer is build-time wired                     |
| Jackson `@JsonProperty` on DTOs    | Requires reflection config | Works — `quarkus-jackson` registers metadata               |
| `ServiceLoader`                    | Fails at runtime           | Works — Quarkus scans `META-INF/services` at build time    |
| LangChain4j AI services            | Requires reflection config | Works — `quarkus-langchain4j` extensions register metadata |

**Key insight:** within Quarkus-managed code, standard annotations and CDI work normally. Constraints only bite when you introduce code Quarkus doesn't know about — custom reflection, third-party libraries without Quarkus extensions, or `hensu-core` internals that bypass the framework.

---

## Decision ladder for adding a dependency

1. **Does a Quarkus extension exist?** Search https://quarkus.io/extensions/ first. If yes, add the extension (not the raw library):
   ```kotlin
   // build.gradle.kts
   implementation("io.quarkus:quarkus-my-library")  // Quarkus extension
   // NOT: implementation("org.example:my-library")  // raw library
   ```
2. **Is it managed by the Quarkus BOM?** If yes, do not override the version — mismatches cause subtle native failures.
3. **No extension, not in BOM** → you own the native-image risk. Proceed to verification below. If the binary fails with `ClassNotFoundException` or `NoSuchMethodException`, add an entry to `hensu-server/src/main/resources/reflect-config.json`:
   ```json
   [
     {
       "name": "com.example.SomeClass",
       "allDeclaredConstructors": true,
       "allPublicMethods": true
     }
   ]
   ```

---

## CDI producer patterns

```java
// SAFE — Quarkus resolves this at build time
@Produces
@Singleton
public WorkflowExecutor workflowExecutor(HensuEnvironment env) {
    return env.getWorkflowExecutor();
}

// SAFE — concrete instantiation
@Produces
@Singleton
public WorkflowRepository workflowRepository() {
    return new InMemoryWorkflowRepository();
}

// UNSAFE — dynamic class loading in a producer
@Produces
@Singleton
public Object dynamicBean() {
    return Class.forName(config.getClassName()).newInstance();  // fails in native
}
```

---

## Server-specific notes

- **Mutiny reactive types are safe.** `Uni`, `Multi`, `BroadcastProcessor` all work in native image — Quarkus handles registration.
- **MCP JSON-RPC uses explicit Jackson.** `JsonRpc` uses `ObjectMapper` directly with `readTree`/`writeValueAsString` — no reflection-based deserialization. Intentionally safe for native image.

---

## Verification commands

```bash
# Full native build (slow — run before releases, not on every change)
./gradlew hensu-server:build -Dquarkus.native.enabled=true -Dquarkus.package.type=native

# Run the produced binary
./hensu-server/build/hensu-server-*-runner

# Quick JVM-mode test (catches most issues except native-specific ones)
./gradlew hensu-server:test

# Native integration tests
./gradlew hensu-server:test -Dquarkus.test.native-image-profile=true
```

### When to run the full native build

- Before tagging a release.
- After adding any non-Quarkus-extension library to a server module.
- After adding or modifying `reflect-config.json`, `resource-config.json`, or `native-image.properties`.

Skip the full native build for pure JVM logic changes — `./gradlew hensu-server:test` is enough for iteration.

---

## Quick reference

| Pattern                                             | Safe  | Notes                                     |
|-----------------------------------------------------|-------|-------------------------------------------|
| `@Inject` / `@Produces`                             | Yes   | Quarkus ArC — build-time CDI              |
| `@ConfigProperty`                                   | Yes   | Build-time processed                      |
| Quarkus extensions                                  | Yes   | Provide native metadata                   |
| Raw third-party libs                                | Maybe | Need `reflect-config.json` if reflective  |
| `ObjectMapper.readTree()`                           | Yes   | No reflection — tree-model parsing        |
| `new ObjectMapper().readValue(json, MyClass.class)` | Maybe | Needs registration unless Quarkus-managed |
| Mutiny `Uni`/`Multi`                                | Yes   | Quarkus-managed                           |
