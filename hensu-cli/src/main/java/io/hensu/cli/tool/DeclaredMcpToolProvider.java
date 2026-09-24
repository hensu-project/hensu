package io.hensu.cli.tool;

import io.hensu.cli.daemon.CredentialsStore;
import io.hensu.cli.review.ApprovalOutcome;
import io.hensu.cli.review.ToolApprovalRequest;
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
import io.hensu.mcp.McpSchemaConverter;
import io.hensu.mcp.McpServerSpec;
import io.hensu.mcp.StdioMcpConnection;
import io.hensu.mcp.StreamableHttpMcpConnection;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
///   names a key in the operator's credential store. It is resolved here and
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
/// @implNote **Mutable.** Holds the run's connections. All mutation happens
/// under one lock; reads of the published catalog are of an immutable snapshot,
/// so parallel branches may call tools concurrently.
/// @see McpConfig for the declaration grammar
/// @see StdioMcpConnection for the local transport
/// @see StreamableHttpMcpConnection for the remote one
@Singleton
public class DeclaredMcpToolProvider implements ToolProvider, PreviewCapable {

    private static final Logger logger = Logger.getLogger(DeclaredMcpToolProvider.class.getName());

    private final CommandCatalog catalog;
    private final SandboxLauncher launcher;
    private final ToolApprovalGate gate;
    private final Map<String, String> hostEnvironment;
    private final CredentialsStore credentials;
    private final ReentrantLock lock = new ReentrantLock();
    private HttpClient httpClient;

    private volatile boolean started;
    private volatile Map<String, ToolDefinition> published = Map.of();
    private volatile Map<String, McpConnection> owners = Map.of();
    private final List<McpConnection> connections = new ArrayList<>();
    private final List<Path> privateHomes = new ArrayList<>();
    private final ToolSourceNotices notices;

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
        this.launcher = Objects.requireNonNull(launcher, "launcher must not be null");
        this.gate = gate;
        this.hostEnvironment = Map.copyOf(hostEnvironment);
        this.notices = Objects.requireNonNull(notices, "notices must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
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
                    connection.callTool(serverSideName(specOf(connection), toolName), arguments));
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
                forget(connection);
            }
            return ToolCallResult.failure(toolName, e.getMessage());
        }
    }

    /// Describes a call to a running server.
    ///
    /// There is no argv to show: the process started before the agent chose
    /// anything, so the honest description is which server is being asked what,
    /// and with which argument names. Values stay out of the frame; the audit
    /// trail, by contrast, records the redacted argument values.
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
                summaryOf(spec, connection, toolName, keys),
                List.of(),
                containmentOf(spec),
                spec != null && spec.unattended(),
                spec != null && spec.approvalRequired());
    }

    /// Names what the operator is about to allow.
    ///
    /// For a remote server that includes who receives it: approving a remote
    /// call is approving disclosure, and a frame that does not say where the
    /// arguments are going is asking for consent to something it has not shown.
    private static String summaryOf(
            McpServerSpec spec, McpConnection connection, String toolName, String keys) {
        return switch (spec) {
            case McpServerSpec.Http http ->
                    "MCP server '"
                            + http.name()
                            + "' at "
                            + http.url()
                            + " ("
                            + serverNameOf(connection)
                            + "): "
                            + toolName
                            + " ("
                            + keys
                            + ") — this call leaves the machine";
            case null, default ->
                    "MCP server '" + name(connection) + "': " + toolName + " (" + keys + ")";
        };
    }

    private static String serverNameOf(McpConnection connection) {
        return connection instanceof StreamableHttpMcpConnection http
                ? http.serverInfo()
                : name(connection);
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
            privateHomes.forEach(DeclaredMcpToolProvider::deleteRecursively);
            privateHomes.clear();
            if (httpClient != null) {
                httpClient.close();
                httpClient = null;
            }
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

        Map<String, ToolDefinition> catalogued = new LinkedHashMap<>();
        Map<String, McpConnection> routes = new LinkedHashMap<>();
        for (McpServerSpec spec : declared) {
            launch(spec, allowedHosts)
                    .ifPresent(connection -> adopt(spec, connection, catalogued, routes));
        }
        published = Map.copyOf(catalogued);
        owners = Map.copyOf(routes);
        if (!connections.isEmpty()) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "mcp-shutdown"));
        }
    }

    private Optional<? extends McpConnection> launch(McpServerSpec spec, Set<String> allowedHosts) {
        return switch (spec) {
            case McpServerSpec.Stdio stdio -> launchStdio(stdio);
            case McpServerSpec.Http http -> dial(http, allowedHosts);
        };
    }

    /// Dials a remote endpoint, resolving its credential first.
    ///
    /// A declared key that is not in the store stops the server here, before any
    /// of its tools reach a catalog. That is deliberate: falling through to an
    /// unauthenticated call would turn a configuration mistake into a request
    /// that leaves the machine without the credential the operator meant to send.
    private Optional<? extends McpConnection> dial(
            McpServerSpec.Http spec, Set<String> allowedHosts) {
        String token = null;
        if (spec.bearerKey() != null) {
            try {
                token = credentials.loadAll().get(spec.bearerKey());
            } catch (IOException e) {
                notifyOperator(
                        "MCP server '"
                                + spec.name()
                                + "' is skipped because its credentials could not be read from "
                                + credentials.path()
                                + ": "
                                + e.getMessage());
                return Optional.empty();
            }
            if (token == null || token.isBlank()) {
                notifyOperator(
                        "MCP server '"
                                + spec.name()
                                + "' declares auth.bearer '"
                                + spec.bearerKey()
                                + "', which is not set in "
                                + credentials.path()
                                + "; its tools are not offered to agents. Set it with 'hensu"
                                + " credentials set "
                                + spec.bearerKey()
                                + "'.");
                return Optional.empty();
            }
        }
        try {
            return Optional.of(
                    StreamableHttpMcpConnection.open(spec, token, allowedHosts, httpClient()));
        } catch (McpException e) {
            String message =
                    "MCP server '"
                            + spec.name()
                            + "' at "
                            + spec.url()
                            + " did not open, its tools are not offered to agents: "
                            + e.getMessage();
            logger.log(Level.WARNING, message, e);
            notices.record(message);
            return Optional.empty();
        }
    }

    /// Builds the run's one outbound client, on first use.
    ///
    /// Called only under {@link #lock}, from the single launch pass.
    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = StreamableHttpMcpConnection.defaultClient();
        }
        return httpClient;
    }

    private Optional<StdioMcpConnection> launchStdio(McpServerSpec.Stdio spec) {
        boolean contained = launcher.isAvailable();
        if (!contained && !uncontainedLaunchApproved(spec)) {
            return Optional.empty();
        }

        Path privateHome;
        try {
            privateHome = Files.createTempDirectory("hensu-mcp-" + spec.name() + "-");
        } catch (IOException e) {
            notifyOperator(
                    "MCP server '"
                            + spec.name()
                            + "' is skipped because it has nowhere to keep a private home: "
                            + e.getMessage());
            return Optional.empty();
        }
        privateHomes.add(privateHome);

        UnaryOperator<List<String>> wrapper =
                contained
                        ? argv ->
                                launcher.wrap(
                                        argv,
                                        spec.sandbox(),
                                        catalog.workingDirectory(),
                                        privateHome)
                        : UnaryOperator.identity();
        try {
            return Optional.of(
                    StdioMcpConnection.open(
                            rootedAt(spec, privateHome),
                            catalog.workingDirectory(),
                            wrapper,
                            hostEnvironment));
        } catch (McpException e) {
            String message =
                    "MCP server '"
                            + spec.name()
                            + "' did not start, its tools are not offered to agents: "
                            + e.getMessage();
            // The stack trace goes to the logger, the sentence to the operator: a launch
            // failure is usually a wrong path or a missing binary, and the cause reads
            // better than the trace.
            logger.log(Level.WARNING, message, e);
            notices.record(message);
            return Optional.empty();
        }
    }

    /// Asks whether a server may start with no containment, because none is available.
    ///
    /// This is the one gate the per-call decorator cannot own. A server process outlives
    /// every call it answers, so "run this uncontained just once" is not a decision that
    /// can be made per call — it is made here, before the process exists, or not at all.
    ///
    /// A run with no reviewer refuses, which leaves the server unstarted and its tools
    /// absent from the catalog. That absence is loud by design: the next node's
    /// declared-versus-available diff names the tools, and the warning below names why.
    ///
    /// @param spec the server that would launch, not null
    /// @return true when a reviewer approved starting it without containment
    private boolean uncontainedLaunchApproved(McpServerSpec.Stdio spec) {
        ApprovalOutcome outcome = ApprovalOutcome.NO_REVIEWER;
        Optional<String> reviewer = gate != null ? gate.attendedReviewer() : Optional.empty();
        if (reviewer.isPresent()) {
            outcome =
                    gate.ask(
                            new ToolApprovalRequest(
                                    reviewer.get(),
                                    "mcp:" + spec.name(),
                                    spec.name(),
                                    "launch MCP server '" + spec.name() + "' without containment",
                                    spec.command(),
                                    "no sandbox backend: " + launcher.unavailabilityReason(),
                                    "no working sandbox backend is available, so approving starts"
                                            + " this server uncontained for the whole run."));
        }
        if (outcome == ApprovalOutcome.APPROVED) {
            notifyOperator(
                    "MCP server '"
                            + spec.name()
                            + "' is starting without containment: a reviewer approved it ("
                            + launcher.unavailabilityReason()
                            + ")");
            return true;
        }
        notifyOperator(
                "MCP server '"
                        + spec.name()
                        + "' is skipped because no sandbox backend is available ("
                        + launcher.unavailabilityReason()
                        + ") and "
                        + (outcome == ApprovalOutcome.REJECTED
                                ? "a reviewer refused starting it uncontained"
                                : "this run has no reviewer to approve starting it uncontained")
                        + "; its tools are not offered to agents");
        return false;
    }

    /// Points a server's `HOME` at the writable directory the sandbox binds for it.
    ///
    /// The working directory is bound read-only unless the server declared
    /// otherwise, so a server given the project as its home would either fail on
    /// its first write or, worse, be handed write access nobody declared. Its own
    /// home is the one place it may always write, exactly as for a command, and
    /// the declaration cannot override it.
    private static McpServerSpec.Stdio rootedAt(McpServerSpec.Stdio spec, Path privateHome) {
        Map<String, String> environment = new LinkedHashMap<>(spec.env());
        environment.put("HOME", privateHome.toString());
        return new McpServerSpec.Stdio(
                spec.name(),
                spec.prefix(),
                spec.command(),
                environment,
                spec.sandbox(),
                spec.startupTimeoutMs(),
                spec.requestTimeoutMs(),
                spec.unattended(),
                spec.approvalRequired());
    }

    private void adopt(
            McpServerSpec spec,
            McpConnection connection,
            Map<String, ToolDefinition> catalogued,
            Map<String, McpConnection> routes) {
        List<McpConnection.McpToolDescriptor> descriptors;
        try {
            descriptors = connection.listTools();
        } catch (McpException e) {
            notifyOperator(
                    "MCP server '"
                            + spec.name()
                            + "' did not answer tools/list, its tools are not offered: "
                            + e.getMessage());
            connection.close();
            return;
        }
        connections.add(connection);
        connection.catalogNotices().forEach(this::notifyOperator);
        for (McpConnection.McpToolDescriptor descriptor : descriptors) {
            String published = spec.prefix() + descriptor.name();
            if (!McpConfig.TOOL_NAME.matcher(published).matches()) {
                notifyOperator(
                        "MCP server '"
                                + spec.name()
                                + "' publishes '"
                                + descriptor.name()
                                + "', which is not a legal tool name"
                                + (spec.prefix().isEmpty()
                                        ? ""
                                        : " once prefixed with '" + spec.prefix() + "'")
                                + "; it is not offered to agents");
                continue;
            }
            if (routes.containsKey(published)) {
                notifyOperator(
                        "MCP server '"
                                + spec.name()
                                + "' publishes '"
                                + published
                                + "', which another declared server already offers; the later one"
                                + " is ignored. Declare a prefix: on one of them to keep both.");
                continue;
            }
            catalogued.put(published, rename(McpSchemaConverter.convert(descriptor), published));
            routes.put(published, connection);
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
        notifyOperator(
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

    /// Republishes a discovered tool under its prefixed name.
    ///
    /// The prefix is a property of the catalog this run assembles, not of the
    /// tool the server published, so the rename happens here rather than in the
    /// schema converter, which both runtimes share.
    private static ToolDefinition rename(ToolDefinition tool, String published) {
        return published.equals(tool.name())
                ? tool
                : new ToolDefinition(
                        published,
                        tool.description(),
                        tool.parameters(),
                        tool.returnType(),
                        tool.rawSchema());
    }

    /// Maps a name the agent used back to the name its server knows.
    private static String serverSideName(McpServerSpec spec, String toolName) {
        String prefix = spec == null ? "" : spec.prefix();
        return prefix.isEmpty() || !toolName.startsWith(prefix)
                ? toolName
                : toolName.substring(prefix.length());
    }

    private static McpServerSpec specOf(McpConnection connection) {
        return switch (connection) {
            case StdioMcpConnection stdio -> stdio.spec();
            case StreamableHttpMcpConnection http -> http.spec();
            case null, default -> null;
        };
    }

    private static String containmentOf(McpServerSpec spec) {
        return switch (spec) {
            case null -> "containment unknown";
            case McpServerSpec.Stdio stdio ->
                    "server launched with network: " + (stdio.sandbox().network() ? "on" : "off");
            case McpServerSpec.Http http ->
                    "no containment applies to a remote server; the declared hosts are the bound,"
                            + " and this one is "
                            + http.host();
        };
    }

    private static String name(McpConnection connection) {
        McpServerSpec spec = specOf(connection);
        return spec != null ? spec.name() : connection.getEndpoint();
    }
}
