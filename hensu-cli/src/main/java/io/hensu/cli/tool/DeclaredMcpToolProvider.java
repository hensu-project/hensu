package io.hensu.cli.tool;

import io.hensu.cli.daemon.CredentialsStore;
import io.hensu.cli.sandbox.SandboxLauncher;
import io.hensu.core.tool.PreviewCapable;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolPreview;
import io.hensu.core.tool.ToolProvider;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpEgressDeniedException;
import io.hensu.mcp.McpException;
import io.hensu.mcp.McpInvalidArgumentException;
import io.hensu.mcp.McpResultRenderer;
import io.hensu.mcp.McpServerSpec;
import io.hensu.mcp.StdioMcpConnection;
import io.hensu.mcp.StreamableHttpMcpConnection;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/// Publishes the tools of every MCP server `mcp.yaml` declares.
///
/// A declaration is one of two shapes, and the difference decides what this
/// class can promise about it. A stdio server is a process this run launches,
/// contains and kills. An HTTP server is somebody else's process on somebody
/// else's host, dialled over Streamable HTTP. Both are started on first use and
/// kept for the length of the run; several consequences of that lifetime are
/// visible here and are deliberate:
///
/// - **Lazy, under a lock.** Nothing starts until an agent's node first resolves
///   its tools, so a run that never uses MCP launches nothing and dials nothing.
///   The guard is a {@link ReentrantLock} rather than `synchronized`, because
///   spawning a subprocess while holding a monitor pins the carrier thread of
///   the Virtual Thread doing it, and rather than a bare `volatile`, because a
///   double-checked launch of external processes races.
/// - **Empty at construction.** {@link io.hensu.core.tool.ToolRouter}'s
///   constructor check therefore cannot see a name this provider will later
///   publish. The authoritative duplicate check is the one that re-runs on every
///   catalog materialization, which fires when the tool loop first resolves
///   tools – the same arrangement the server's tenant-scoped provider relies on.
/// - **Containment is decided at launch, where it exists at all.** A stdio
///   server process outlives every call it serves, so there is no per-call
///   sandbox decision to make; when no backend is available the server is
///   skipped rather than started uncontained. A remote server has no process to
///   contain, and no sandbox of ours reaches another host: its bound is the set
///   of hosts declared across this document, enforced by the connection when an
///   endpoint tries to redirect a call somewhere else.
/// - **A credential is resolved once, by name.** An `auth: {bearer: KEY}` entry
///   names a key in the operator's credential store. It is resolved at launch and
///   injected into the connection, so `hensu-mcp` never learns that a credential
///   store exists. A key that is not in the store stops that server before any
///   of its tools are published – it contributes nothing rather than falling
///   through to an unauthenticated call.
/// - **No restart policy.** A server that exits, or an endpoint that stops
///   answering, reports failure and drops out of the catalog, so the loop's
///   declared-versus-available diff names it. Quietly respawning a process that
///   keeps crashing is worse than a loud absence.
///
/// A missing `mcp.yaml`, a malformed one, a server that will not start and an
/// endpoint that cannot be reached all end the same way: fewer tools and a
/// recorded reason, never a thrown exception. A provider that throws from
/// `tools()` would hide the rest of the catalog too.
///
/// ### Contracts
/// - **Precondition**: the catalog has been pointed at a working directory
/// - **Postcondition**: every connection this opened is closed by {@link #shutdown}
/// - **Invariant**: no request leaves for a host no declaration named
///
/// Launching a local server, dialling a remote one and assembling their tools
/// into one catalog each live in their own package-private type
/// (`StdioServerLauncher`, `HttpServerDialer`, `McpToolCatalog`); this class
/// owns only the lifetime they share.
///
/// @implNote **Mutable.** Holds the run's connections. All mutation happens
/// under one lock; reads of the published catalog are of an immutable
/// snapshot, so parallel branches may call tools concurrently.
/// @see McpConfig for the declaration grammar
/// @see StdioMcpConnection for the local transport
/// @see StreamableHttpMcpConnection for the remote one
@Singleton
public class DeclaredMcpToolProvider implements ToolProvider, PreviewCapable {

    private static final Logger logger = Logger.getLogger(DeclaredMcpToolProvider.class.getName());

    private final CommandCatalog catalog;
    private final ToolSourceNotices notices;
    private final StdioServerLauncher stdio;
    private final HttpServerDialer http;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<McpConnection> connections = new ArrayList<>();

    private volatile boolean started;
    private volatile McpToolCatalog published = McpToolCatalog.EMPTY;

    /// Creates a provider over the shared catalog, the platform sandbox, the host
    /// environment and the operator's credential store.
    ///
    /// @param catalog the shared catalog, which owns the working directory, not null
    /// @param gate the approval policy consulted when containment is unavailable, may be
    ///     null — a provider built without one never launches an uncontained server
    /// @param notices where the reasons a server offers no tools are recorded, not null
    @Inject
    public DeclaredMcpToolProvider(
            CommandCatalog catalog, ToolApprovalGate gate, ToolSourceNotices notices) {
        this(
                catalog,
                SandboxLauncher.forCurrentOs(),
                gate,
                System.getenv(),
                notices,
                CredentialsStore.ofDefaults());
    }

    /// Creates a provider with every collaborator explicit.
    ///
    /// The only test seam. There is deliberately no shorter form: a convenience
    /// overload would have to default some collaborator, and defaulting the credential
    /// store means a test quietly reading the operator's real one.
    ///
    /// @param catalog the shared catalog, not null
    /// @param launcher the containment backend applied at launch, not null
    /// @param gate the approval policy consulted when containment is unavailable, may be
    ///     null — a provider built without one never launches an uncontained server
    /// @param hostEnvironment the environment servers draw their hermetic base
    ///     from, not null
    /// @param notices where the reasons a server offers no tools are recorded, not null
    /// @param credentials where an `auth: {bearer: KEY}` entry is resolved, not null
    DeclaredMcpToolProvider(
            CommandCatalog catalog,
            SandboxLauncher launcher,
            ToolApprovalGate gate,
            Map<String, String> hostEnvironment,
            ToolSourceNotices notices,
            CredentialsStore credentials) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.notices = Objects.requireNonNull(notices, "notices must not be null");
        this.stdio =
                new StdioServerLauncher(
                        catalog,
                        Objects.requireNonNull(launcher, "launcher must not be null"),
                        gate,
                        hostEnvironment,
                        this::notifyOperator);
        this.http =
                new HttpServerDialer(
                        Objects.requireNonNull(credentials, "credentials must not be null"),
                        this::notifyOperator);
    }

    /// Logs an operator-facing reason and records it where the run can print it.
    ///
    /// The CLI ships `quarkus.log.console.level=OFF`, so a warning alone reaches nobody.
    ///
    /// @param message the reason a declared tool is not available, not null
    private void notifyOperator(String message) {
        logger.warning(message);
        notices.record(message);
    }

    /// Returns the tools of every server that started, launching them on first call.
    ///
    /// @return the union of the running servers' catalogs, never null (may be empty)
    @Override
    public List<ToolDefinition> tools() {
        ensureStarted();
        return published.tools();
    }

    /// Returns the tools of servers that are already running, starting none.
    ///
    /// Wiring the engine must not launch anything, so the router's
    /// construction-time duplicate check reads this and sees nothing until the
    /// first node actually resolves its tools.
    ///
    /// @return the catalog of servers started so far, never null (may be empty)
    @Override
    public List<ToolDefinition> settledTools() {
        return published.tools();
    }

    /// Returns whether a running server publishes this name.
    ///
    /// @param toolName the tool identifier to check, not null
    /// @return true if a started server offers it
    @Override
    public boolean provides(String toolName) {
        ensureStarted();
        return published.route(toolName) != null;
    }

    /// Invokes a tool on the server that published it.
    ///
    /// @param toolName the tool the agent chose, not null
    /// @param arguments the agent's arguments, not null (may be empty)
    /// @param context the workflow state context, not null and unused – an MCP
    ///     call carries only the arguments the agent chose
    /// @return the rendered server result, or a typed failure, never null
    @Override
    public ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context) {
        ensureStarted();
        McpRoute route = published.route(toolName);
        if (route == null) {
            return ToolCallResult.of(
                    toolName,
                    ToolCallStatus.UNKNOWN_TOOL,
                    null,
                    "'" + toolName + "' is not offered by any running MCP server",
                    null);
        }
        McpConnection connection = route.connection();
        if (!connection.isConnected()) {
            forget(route);
            return ToolCallResult.failure(
                    toolName, "MCP server '" + route.name() + "' is not running");
        }
        try {
            return McpResultRenderer.toResult(
                    toolName, connection.callTool(route.serverSideName(toolName), arguments));
        } catch (McpEgressDeniedException e) {
            // A refused destination is a policy decision, not a transport fault:
            // the connection is fine and the next call to this server may work.
            notifyOperator(e.getMessage());
            return ToolCallResult.of(toolName, ToolCallStatus.DENIED, null, e.getMessage(), null);
        } catch (McpInvalidArgumentException e) {
            // Nothing was sent. The agent chose an argument the server's own schema
            // cannot carry in its mirrored header, and can choose again.
            return ToolCallResult.of(
                    toolName, ToolCallStatus.VALIDATION_FAILED, null, e.getMessage(), null);
        } catch (McpException e) {
            if (!connection.isConnected()) {
                forget(route);
            }
            return ToolCallResult.failure(toolName, e.getMessage());
        }
    }

    /// Describes a call to a running server.
    ///
    /// @param toolName the tool identifier to describe, not null
    /// @param arguments the agent's arguments, not null (may be empty)
    /// @return the description of the call, never null
    /// @throws IllegalArgumentException if no running server offers the name
    @Override
    public ToolPreview preview(String toolName, Map<String, Object> arguments) {
        ensureStarted();
        McpRoute route = published.route(toolName);
        if (route == null) {
            throw new IllegalArgumentException("Not an MCP tool of this run: " + toolName);
        }
        return route.preview(toolName, arguments);
    }

    /// Returns the declaration behind a published tool.
    ///
    /// @param toolName the tool identifier to look up, not null
    /// @return the owning server's declaration, or null when no server offers the name
    /// @apiNote The approval gate reads a server's `unattended` and `approval`
    ///     flags through this, because policy for an MCP tool is the server's,
    ///     not the tool's.
    public McpServerSpec serverOf(String toolName) {
        ensureStarted();
        McpRoute route = published.route(toolName);
        return route == null ? null : route.spec();
    }

    /// Closes every server this run started.
    ///
    /// @apiNote **Side effects**: destroys each server's process tree. Idempotent,
    ///     so the shutdown hook and the container's own callback may both fire.
    @PreDestroy
    public void shutdown() {
        lock.lock();
        try {
            connections.forEach(McpConnection::close);
            connections.clear();
            stdio.close();
            http.close();
            published = McpToolCatalog.EMPTY;
        } finally {
            lock.unlock();
        }
    }

    // ----------------------------------------------------------------- launching

    private void ensureStarted() {
        if (started) {
            return;
        }
        lock.lock();
        try {
            if (started) {
                return;
            }
            started = true;
            launchAll();
        } finally {
            lock.unlock();
        }
    }

    private void launchAll() {
        List<McpServerSpec> declared;
        try {
            declared = McpConfig.load(catalog.workingDirectory());
        } catch (RuntimeException e) {
            notifyOperator(
                    "Could not read "
                            + McpConfig.FILE_NAME
                            + ", no MCP tools are available: "
                            + e.getMessage());
            return;
        }
        if (declared.isEmpty()) {
            return;
        }

        // The set of hosts named across the document is the allowlist for the run –
        // the remote analogue of "the catalog is the allowlist".
        Set<String> allowedHosts =
                declared.stream()
                        .filter(McpServerSpec.Http.class::isInstance)
                        .map(spec -> ((McpServerSpec.Http) spec).host())
                        .collect(Collectors.toUnmodifiableSet());

        McpToolCatalog assembled = McpToolCatalog.EMPTY;
        for (McpServerSpec spec : declared) {
            Optional<McpRoute> route =
                    switch (spec) {
                        case McpServerSpec.Stdio local -> stdio.launch(local);
                        case McpServerSpec.Http remote -> http.dial(remote, allowedHosts);
                    };
            if (route.isPresent()) {
                assembled = adopt(route.get(), assembled);
            }
        }
        published = assembled;
        if (!connections.isEmpty()) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "mcp-shutdown"));
        }
    }

    private McpToolCatalog adopt(McpRoute route, McpToolCatalog assembled) {
        McpConnection connection = route.connection();
        List<McpConnection.McpToolDescriptor> descriptors;
        try {
            descriptors = connection.listTools();
        } catch (McpException e) {
            notifyOperator(
                    "MCP server '"
                            + route.name()
                            + "' did not answer tools/list, its tools are not offered: "
                            + e.getMessage());
            connection.close();
            return assembled;
        }
        connections.add(connection);
        connection.catalogNotices().forEach(this::notifyOperator);
        logger.info("MCP server '" + route.name() + "' offers " + descriptors.size() + " tool(s)");
        return assembled.with(route, descriptors, this::notifyOperator);
    }

    /// Drops a dead server's tools so the loop's diff names them as absent.
    private void forget(McpRoute route) {
        lock.lock();
        try {
            // Read-modify-write is safe: every write to `published` holds this lock;
            // `volatile` only lets lock-free readers see a whole snapshot.
            published = published.without(route.connection());
            connections.remove(route.connection());
        } finally {
            lock.unlock();
        }
        notifyOperator(
                "MCP server '"
                        + route.name()
                        + "' is no longer running; its tools have left the catalog");
    }
}
