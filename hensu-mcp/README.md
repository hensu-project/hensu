# Hensu MCP

Runtime-agnostic Model Context Protocol surface shared by the CLI and the server.

## Overview

The `hensu-mcp` module holds the parts of MCP that do not depend on how a connection is
established: the JSON-RPC message format, the translation between MCP's JSON Schema and the
engine's `ToolDefinition`, and the rendering of a `tools/call` response into text an agent can
read.

Transport stays with whoever owns it. The server reaches tenant MCP servers over an SSE
split-pipe and pools those connections; the CLI will launch stdio servers as child processes. Both
speak the same protocol above that line, so everything above it lives here and neither runtime
reimplements it. The server is the only consumer today — the CLI side is not wired yet.

The module carries no CDI annotations and no framework types. Its classes are plain objects the
server publishes through producers and the CLI constructs directly.

```mermaid
flowchart LR
    subgraph shared["hensu-mcp"]
        direction TB
        rpc(["JsonRpc\n(message format)"])
        conv(["McpSchemaConverter\n(schema to ToolDefinition)"])
        rend(["McpResultRenderer\n(response to text)"])
    end

    subgraph server["hensu-server"]
        direction TB
        sse(["SSE split-pipe\n(tenant servers)"])
    end

    subgraph cli["hensu-cli (planned)"]
        direction TB
        stdio(["stdio\n(child processes)"])
    end

    sse --> shared
    stdio --> shared

    style shared fill:#2c2c2e, stroke:#0A84FF, color:#ebebf5, stroke-width:1px
    style server fill:#2c2c2e, stroke:#3a3a3c, color:#ebebf5, stroke-width:1px
    style cli    fill:#2c2c2e, stroke:#3a3a3c, color:#ebebf5, stroke-width:1px

    style rpc   fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style conv  fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style rend  fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style sse   fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
    style stdio fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px

    linkStyle default stroke:#0A84FF, stroke-width:1px
```

## Entry Points

### `JsonRpc` — message construction and parsing

```java
JsonRpc jsonRpc = new JsonRpc(mapper);

String request = jsonRpc.createRequest(id, "tools/call", params);
Map<String, Object> result = jsonRpc.parseResult(responseJson);   // throws McpException on error
```

Parsing stays on Jackson's tree model (`readTree`), never data binding, so no reflective
deserialization enters the native image. The class is deliberately not a CDI bean: the server
exposes it via a `@Produces` method in `ServerConfiguration`, and the CLI constructs one.

### `McpSchemaConverter` — MCP schema to engine model

```java
ToolDefinition tool = McpSchemaConverter.convert(descriptor);
```

Reads the parts of JSON Schema the engine can act on — property name, `type`, `description`,
`default`, and membership in the schema's `required` array. Richer constructs are left to the MCP
server to enforce. A malformed schema degrades to "no parameters" rather than throwing, so one bad
descriptor cannot take down a whole catalog.

Discovered parameters are never marked `sensitive`: MCP has no way to express that a value is a
secret, and claiming otherwise would make the audit trail redact values the server itself
publishes in plain schema. Only locally declared tools can make that claim.

### `McpResultRenderer` — response to agent-readable text

```java
ToolCallResult result = McpResultRenderer.toResult(toolName, response);
```

An MCP result is an ordered array of typed content blocks. Passing that map to a model directly
would put a Java `Map.toString` — braces, equals signs, identity hashes — into the context window.
The renderer flattens it instead:

| Block                            | Rendered as                             |
|----------------------------------|-----------------------------------------|
| `text`                           | its text, in array order, one per line  |
| `image`, `audio`                 | `[image: image/png]`                    |
| `resource`, `resource_link`      | `[resource: file:///etc/hosts (text/plain)]` |
| unrecognized payload             | canonical JSON                          |

Non-text blocks carry bytes a text completion cannot consume, so only the block type and whatever
locates it survive. A response setting `isError: true` becomes `ToolCallStatus.FAILURE` carrying
the rendered text as its error message, so the agent sees what went wrong.

## Module Structure

```
hensu-mcp/src/main/java/io/hensu/mcp/
├── JsonRpc.java                # JSON-RPC 2.0 message construction and tree-model parsing
├── McpConnection.java          # Connection contract + McpToolDescriptor record
├── McpConnectionFactory.java   # Transport-specific connection establishment
├── McpException.java           # Protocol, connection, and tool-invocation failures
├── McpSchemaConverter.java     # MCP JSON Schema to ToolDefinition
└── McpResultRenderer.java      # tools/call response to ToolCallResult
```

## Consumers

| Runtime                 | Transport                         | Provider                                  | Status  |
|-------------------------|-----------------------------------|-------------------------------------------|---------|
| `hensu-server`          | SSE split-pipe, pooled per tenant | `McpToolProvider` — tenant-scoped catalog | wired   |
| `hensu-cli`             | stdio child processes             | `LocalMcpToolProvider`                    | planned |

Each contributes a `ToolProvider` to the engine's `ToolRouter`, so an agent cannot tell which
transport produced a result. That is the reason the rendering rules above live in this module
rather than beside either transport.

## Dependencies

- `hensu-core` (API dependency) — for `ToolDefinition`, `ToolCallResult`, `ToolCallStatus`
- Jackson BOM 2.20.1 (`jackson-databind`) — resolved by the Quarkus BOM inside `hensu-server`
- Test: JUnit 5, AssertJ

## See Also

- [Server Developer Guide](../docs/developer-guide-server.md) — MCP transport, tenant scoping, dynamic tool discovery
- [Core Developer Guide](../docs/developer-guide-core.md) — the `ToolProvider` seam and the agent tool loop
