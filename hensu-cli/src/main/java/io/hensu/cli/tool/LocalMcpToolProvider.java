package io.hensu.cli.tool;

import io.hensu.cli.sandbox.SandboxLauncher;
import io.hensu.core.tool.PreviewCapable;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolPreview;
import io.hensu.core.tool.ToolProvider;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpException;
import io.hensu.mcp.McpResultRenderer;
import io.hensu.mcp.McpSchemaConverter;
import io.hensu.mcp.McpServerSpec;
import io.hensu.mcp.StdioMcpConnection;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/// Publishes the tools of MCP servers this run launched itself.
///
/// Servers declared in `mcp.yaml` are started on first use and kept for the
/// length of the run. Several consequences of that lifetime are visible in this
/// class and are deliberate:
///
/// - **Lazy, under a lock.** Nothing starts until an agent's node first resolves
///   its tools, so a run that never uses MCP launches nothing. The guard is a
///   {@link ReentrantLock} rather than `synchronized`, because spawning a
///   subprocess while holding a monitor pins the carrier thread of the Virtual
///   Thread doing it, and rather than a bare `volatile`, because a
///   double-checked launch of external processes races.
/// - **Empty at construction.** {@link io.hensu.core.tool.ToolRouter}'s
///   constructor check therefore cannot see a name this provider will later
///   publish. The authoritative duplicate check is the one that re-runs on every
///   catalog materialization, which fires when the tool loop first resolves
///   tools – the same arrangement the server's tenant-scoped provider relies on.
/// - **Containment is decided at launch.** A server process outlives every call
///   it serves, so there is no per-call sandbox decision to make. When no
///   backend is available the server is skipped with a warning and contributes
///   no tools, rather than being started uncontained.
/// - **No restart policy.** A server that exits reports failure and drops out of
///   the catalog, so the loop's declared-versus-available diff names it. Quietly
///   respawning a process that keeps crashing is worse than a loud absence.
///
/// A missing `mcp.yaml`, a malformed one, or a server that will not start all
/// end the same way: fewer tools and a logged reason, never a thrown exception.
/// A provider that throws from `tools()` would hide the rest of the catalog too.
///
/// ### Contracts
/// - **Precondition**: the catalog has been pointed at a working directory
/// - **Postcondition**: every connection this opened is closed by {@link #shutdown}
///
/// @implNote **Mutable.** Holds the run's connections. All mutation happens
/// under one lock; reads of the published catalog are of an immutable snapshot,
/// so parallel branches may call tools concurrently.
/// @see McpConfig for the declaration grammar
/// @see StdioMcpConnection for the transport
@Singleton
public class LocalMcpToolProvider implements ToolProvider, PreviewCapable {

    private static final Logger logger = Logger.getLogger(LocalMcpToolProvider.class.getName());

    private final CommandCatalog catalog;
    private final SandboxLauncher launcher;
    private final Map<String, String> hostEnvironment;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile boolean started;
    private volatile Map<String, ToolDefinition> published = Map.of();
    private volatile Map<String, McpConnection> owners = Map.of();
    private final List<McpConnection> connections = new ArrayList<>();
    private final List<Path> privateHomes = new ArrayList<>();

    /// Creates a provider over the shared catalog and the platform sandbox.
    ///
    /// @param catalog the shared catalog, which owns the working directory, not null
    @Inject
    public LocalMcpToolProvider(CommandCatalog catalog) {
        this(catalog, SandboxLauncher.forCurrentOs(), System.getenv());
    }

    /// Creates a provider over an explicit backend and host environment.
    ///
    /// @param catalog the shared catalog, not null
    /// @param launcher the containment backend applied at launch, not null
    /// @param hostEnvironment the environment servers draw their hermetic base
    ///     from, not null
    /// @apiNote Test seam, so launch behaviour can be exercised without the
    ///     host's real sandbox or real environment.
    public LocalMcpToolProvider(
            CommandCatalog catalog, SandboxLauncher launcher, Map<String, String> hostEnvironment) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.launcher = Objects.requireNonNull(launcher, "launcher must not be null");
        this.hostEnvironment = Map.copyOf(hostEnvironment);
    }

    /// Returns the tools of every server that started, launching them on first call.
    ///
    /// @return the union of the running servers' catalogs, never null (may be empty)
    @Override
    public List<ToolDefinition> tools() {
        ensureStarted();
        return List.copyOf(published.values());
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
        return List.copyOf(published.values());
    }

    /// Returns whether a running server publishes this name.
    ///
    /// @param toolName the tool identifier to check, not null
    /// @return true if a started server offers it
    @Override
    public boolean provides(String toolName) {
        ensureStarted();
        return owners.containsKey(toolName);
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
        McpConnection connection = owners.get(toolName);
        if (connection == null) {
            return ToolCallResult.of(
                    toolName,
                    ToolCallStatus.UNKNOWN_TOOL,
                    null,
                    "'" + toolName + "' is not offered by any running MCP server",
                    null);
        }
        if (!connection.isConnected()) {
            forget(connection);
            return ToolCallResult.failure(
                    toolName, "MCP server '" + name(connection) + "' is not running");
        }
        try {
            return McpResultRenderer.toResult(
                    toolName,
                    connection.callTool(toolName, arguments != null ? arguments : Map.of()));
        } catch (McpException e) {
            if (!connection.isConnected()) {
                forget(connection);
            }
            return ToolCallResult.failure(toolName, e.getMessage());
        }
    }

    /// Describes a call to a running server.
    ///
    /// There is no argv to show: the process started before the agent chose
    /// anything, so the honest description is which server is being asked what,
    /// and with which argument names. Values stay out – the same rule the audit
    /// trail follows.
    ///
    /// @param toolName the tool identifier to describe, not null
    /// @param arguments the agent's arguments, not null (may be empty)
    /// @return the description of the call, never null
    /// @throws IllegalArgumentException if no running server offers the name
    @Override
    public ToolPreview preview(String toolName, Map<String, Object> arguments) {
        ensureStarted();
        McpConnection connection = owners.get(toolName);
        if (connection == null) {
            throw new IllegalArgumentException("Not an MCP tool of this run: " + toolName);
        }
        McpServerSpec spec = specOf(connection);
        String keys =
                arguments == null || arguments.isEmpty()
                        ? "no arguments"
                        : String.join(", ", arguments.keySet());
        return new ToolPreview(
                "MCP server '" + name(connection) + "': " + toolName + " (" + keys + ")",
                List.of(),
                "server launched with network: "
                        + (spec != null && spec.sandbox().network() ? "on" : "off"),
                spec != null && spec.unattended());
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
        return specOf(owners.get(toolName));
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
            privateHomes.forEach(LocalMcpToolProvider::deleteRecursively);
            privateHomes.clear();
            published = Map.of();
            owners = Map.of();
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
            logger.warning(
                    "Could not read "
                            + McpConfig.FILE_NAME
                            + ", no MCP tools are available: "
                            + e.getMessage());
            return;
        }
        if (declared.isEmpty()) {
            return;
        }

        Map<String, ToolDefinition> catalogued = new LinkedHashMap<>();
        Map<String, McpConnection> routes = new LinkedHashMap<>();
        for (McpServerSpec spec : declared) {
            launch(spec).ifPresent(connection -> adopt(spec, connection, catalogued, routes));
        }
        published = Map.copyOf(catalogued);
        owners = Map.copyOf(routes);
        if (!connections.isEmpty()) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "mcp-shutdown"));
        }
    }

    private Optional<StdioMcpConnection> launch(McpServerSpec spec) {
        if (!launcher.isAvailable()) {
            // A long-lived server cannot be contained per call, so an unavailable
            // backend is decided here, once: the server does not start.
            logger.warning(
                    "MCP server '"
                            + spec.name()
                            + "' is skipped because no sandbox backend is available ("
                            + launcher.unavailabilityReason()
                            + "); its tools are not offered to agents");
            return Optional.empty();
        }

        Path privateHome;
        try {
            privateHome = Files.createTempDirectory("hensu-mcp-" + spec.name() + "-");
        } catch (IOException e) {
            logger.warning(
                    "MCP server '"
                            + spec.name()
                            + "' is skipped because it has nowhere to keep a private home: "
                            + e.getMessage());
            return Optional.empty();
        }
        privateHomes.add(privateHome);

        UnaryOperator<List<String>> wrapper =
                argv ->
                        launcher.wrap(
                                argv, spec.sandbox(), catalog.workingDirectory(), privateHome);
        try {
            return Optional.of(
                    StdioMcpConnection.open(
                            rootedAt(spec, privateHome),
                            catalog.workingDirectory(),
                            wrapper,
                            hostEnvironment));
        } catch (McpException e) {
            logger.log(
                    Level.WARNING,
                    "MCP server '"
                            + spec.name()
                            + "' did not start, its tools are not offered to agents: "
                            + e.getMessage(),
                    e);
            return Optional.empty();
        }
    }

    /// Points a server's `HOME` at the writable directory the sandbox binds for it.
    ///
    /// The working directory is bound read-only unless the server declared
    /// otherwise, so a server given the project as its home would either fail on
    /// its first write or, worse, be handed write access nobody declared. Its own
    /// home is the one place it may always write, exactly as for a command, and
    /// the declaration cannot override it.
    private static McpServerSpec rootedAt(McpServerSpec spec, Path privateHome) {
        Map<String, String> environment = new LinkedHashMap<>(spec.env());
        environment.put("HOME", privateHome.toString());
        return new McpServerSpec(
                spec.name(),
                spec.command(),
                environment,
                spec.sandbox(),
                spec.requestTimeoutMs(),
                spec.unattended(),
                spec.approvalRequired());
    }

    private void adopt(
            McpServerSpec spec,
            StdioMcpConnection connection,
            Map<String, ToolDefinition> catalogued,
            Map<String, McpConnection> routes) {
        List<McpConnection.McpToolDescriptor> descriptors;
        try {
            descriptors = connection.listTools();
        } catch (McpException e) {
            logger.warning(
                    "MCP server '"
                            + spec.name()
                            + "' did not answer tools/list, its tools are not offered: "
                            + e.getMessage());
            connection.close();
            return;
        }
        connections.add(connection);
        for (McpConnection.McpToolDescriptor descriptor : descriptors) {
            if (routes.containsKey(descriptor.name())) {
                logger.warning(
                        "MCP server '"
                                + spec.name()
                                + "' publishes '"
                                + descriptor.name()
                                + "', which another declared server already offers; the later one"
                                + " is ignored");
                continue;
            }
            catalogued.put(descriptor.name(), McpSchemaConverter.convert(descriptor));
            routes.put(descriptor.name(), connection);
        }
        logger.info("MCP server '" + spec.name() + "' offers " + descriptors.size() + " tool(s)");
    }

    /// Drops a dead server's tools so the loop's diff names them as absent.
    private void forget(McpConnection connection) {
        lock.lock();
        try {
            Map<String, McpConnection> remainingRoutes = new LinkedHashMap<>(owners);
            Map<String, ToolDefinition> remainingTools = new LinkedHashMap<>(published);
            remainingRoutes.entrySet().removeIf(entry -> entry.getValue() == connection);
            remainingTools.keySet().retainAll(remainingRoutes.keySet());
            owners = Map.copyOf(remainingRoutes);
            published = Map.copyOf(remainingTools);
            connections.remove(connection);
        } finally {
            lock.unlock();
        }
        logger.warning(
                "MCP server '"
                        + name(connection)
                        + "' is no longer running; its tools have left the catalog");
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    logger.fine(
                                            () ->
                                                    "Could not remove "
                                                            + path
                                                            + ": "
                                                            + e.getMessage());
                                }
                            });
        } catch (IOException e) {
            logger.fine(() -> "Could not remove " + root + ": " + e.getMessage());
        }
    }

    private static McpServerSpec specOf(McpConnection connection) {
        return connection instanceof StdioMcpConnection stdio ? stdio.spec() : null;
    }

    private static String name(McpConnection connection) {
        McpServerSpec spec = specOf(connection);
        return spec != null ? spec.name() : connection.getEndpoint();
    }
}
