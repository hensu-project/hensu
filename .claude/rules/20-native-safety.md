# 20-native-safety.md

# GraalVM Native Image

The server is deployed as a GraalVM native image via Quarkus. All server code — and any dependency it pulls in — must be
native-image safe. See
the [hensu-core Developer Guide](../../docs/developer-guide-core.md#graalvm-native-image-constraints) for the
foundational rules (no reflection, no classpath scanning, no dynamic proxies, no runtime bytecode generation). This
section covers **server-specific** concerns.

## How Quarkus Changes the Picture

Quarkus performs heavy build-time processing that relaxes some raw GraalVM constraints:

| Feature                            | Raw GraalVM                | With Quarkus                                               |
|------------------------------------|----------------------------|------------------------------------------------------------|
| CDI injection (`@Inject`)          | Requires reflection config | Works — Quarkus resolves beans at build time (ArC)         |
| `@ConfigProperty`                  | Requires reflection config | Works — processed at build time                            |
| JAX-RS resources (`@Path`, `@GET`) | Requires reflection config | Works — REST layer is build-time wired                     |
| Jackson `@JsonProperty` on DTOs    | Requires reflection config | Works — `quarkus-jackson` registers metadata               |
| `ServiceLoader`                    | Fails at runtime           | Works — Quarkus scans `META-INF/services` at build time    |
| LangChain4j AI services            | Requires reflection config | Works — `quarkus-langchain4j` extensions register metadata |

**Key insight**: Within Quarkus-managed code, standard annotations and CDI work normally. The constraints only bite when
you introduce code that Quarkus doesn't know about — custom reflection, third-party libraries without Quarkus
extensions, or `hensu-core` internals that bypass the framework.

## Adding New Dependencies

When adding a new library to `hensu-server`:

1. **Check if a Quarkus extension exists.** Search [extensions catalog](https://quarkus.io/extensions/) first.
   Extensions provide build-time metadata, so you get native-image support automatically.

2. **If an extension exists**, add the Quarkus extension (not the raw library):
   ```kotlin
   // build.gradle.kts
   implementation("io.quarkus:quarkus-my-library")  // Quarkus extension
   // NOT: implementation("org.example:my-library")  // raw library
   ```

3. **If no extension exists**, you must verify native-image compatibility:
    - Run `./gradlew hensu-server:build -Dquarkus.native.enabled=true -Dquarkus.package.type=native`
    - Test the binary: `./hensu-server/build/hensu-server-*-runner`
    - If it fails with `ClassNotFoundException` or `NoSuchMethodException`, add reflection configuration:
      ```json
      // src/main/resources/reflect-config.json
      [
        {
          "name": "com.example.SomeClass",
          "allDeclaredConstructors": true,
          "allPublicMethods": true
        }
      ]
      ```

4. **Pin the version to match Quarkus BOM.** If the library is managed by the Quarkus BOM (e.g., Jackson, Vert.x), do
   not override the version. Mismatched versions cause subtle native-image failures.

## CDI Producers and Native Image

CDI producers in `ServerConfiguration` are native-image safe because Quarkus processes them at build time. Follow these
patterns:

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

### Server-Specific Notes

**Mutiny reactive types are safe.** `Uni`, `Multi`, `BroadcastProcessor` all work in native image — Quarkus handles
their registration.

**MCP JSON-RPC uses explicit Jackson.** The `JsonRpc` class uses `ObjectMapper` directly with `readTree`/
`writeValueAsString` — no reflection-based deserialization. This is intentionally safe for native image.

### Verifying Native Image Compatibility

```bash
# Full native build (slow — run before releases, not on every change)
./gradlew hensu-server:build -Dquarkus.native.enabled=true -Dquarkus.package.type=native

# Quick JVM-mode test (catches most issues except native-specific ones)
./gradlew hensu-server:test

# Native integration tests
./gradlew hensu-server:test -Dquarkus.test.native-image-profile=true
```

### Quick Reference (Server-Specific)

| Pattern                                             | Safe  | Notes                                     |
|-----------------------------------------------------|-------|-------------------------------------------|
| `@Inject` / `@Produces`                             | Yes   | Quarkus ArC — build-time CDI              |
| `@ConfigProperty`                                   | Yes   | Build-time processed                      |
| Quarkus extensions                                  | Yes   | Provide native metadata                   |
| Raw third-party libs                                | Maybe | Need `reflect-config.json` if reflective  |
| `ObjectMapper.readTree()`                           | Yes   | No reflection — tree-model parsing        |
| `new ObjectMapper().readValue(json, MyClass.class)` | Maybe | Needs registration unless Quarkus-managed |
| Mutiny `Uni`/`Multi`                                | Yes   | Quarkus-managed                           |
