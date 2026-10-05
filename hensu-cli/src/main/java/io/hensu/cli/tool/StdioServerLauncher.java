package io.hensu.cli.tool;

import io.hensu.cli.review.ApprovalOutcome;
import io.hensu.cli.review.ToolApprovalRequest;
import io.hensu.cli.sandbox.SandboxLauncher;
import io.hensu.mcp.McpException;
import io.hensu.mcp.McpServerSpec;
import io.hensu.mcp.StdioMcpConnection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/// Launches the local MCP servers a run declares, each inside the platform sandbox.
///
/// A server process outlives every call it serves, so containment is decided
/// here, once, before the process exists: when no backend is available the
/// server is skipped rather than started uncontained, unless a reviewer
/// approves that launch. Each server gets its own writable home, removed when
/// the server is stopped.
///
/// @implNote **Not thread-safe.** Called only from the provider's launch passes
///     and its shutdown, all under the provider's lock.
final class StdioServerLauncher implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(StdioServerLauncher.class.getName());

    /// The one thread every server process is spawned from.
    ///
    /// `bwrap --die-with-parent` has the kernel kill the sandbox when its parent
    /// dies, and on Linux that parent is the thread that spawned the process, not
    /// the process itself. A Virtual Thread's carrier is a pool thread that retires
    /// after about thirty idle seconds, so a server spawned from one died shortly
    /// after the call that launched it – invisible in a short run, fatal to a
    /// daemon that keeps servers between runs. This is a platform thread that never
    /// exits: work reaches it as a future, so a failing launch cannot make the pool
    /// replace its worker, and nothing shuts it down.
    private static final ExecutorService SPAWNER =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("mcp-spawner").daemon().factory());

    private final CommandCatalog catalog;
    private final SandboxLauncher sandbox;
    private final ToolApprovalGate gate;
    private final Map<String, String> hostEnvironment;
    private final Consumer<String> operator;
    private final List<Path> privateHomes = new ArrayList<>();

    /// Creates a launcher over the platform sandbox.
    ///
    /// @param catalog the shared catalog, which owns the working directory, not null
    /// @param sandbox the containment backend applied at launch, not null
    /// @param gate the approval policy consulted when containment is unavailable, may be
    ///     null — a launcher built without one never starts an uncontained server
    /// @param hostEnvironment the environment servers draw their hermetic base from, not null
    /// @param operator receives every reason a server is not started, not null
    StdioServerLauncher(
            CommandCatalog catalog,
            SandboxLauncher sandbox,
            ToolApprovalGate gate,
            Map<String, String> hostEnvironment,
            Consumer<String> operator) {
        this.catalog = catalog;
        this.sandbox = sandbox;
        this.gate = gate;
        this.hostEnvironment = Map.copyOf(hostEnvironment);
        this.operator = operator;
    }

    /// Launches one local server.
    ///
    /// @param spec the declaration, not null
    /// @return the route to the server, or empty when it was skipped or did not start
    Optional<McpRoute> launch(McpServerSpec.Stdio spec) {
        boolean contained = sandbox.isAvailable();
        if (!contained && !uncontainedLaunchApproved(spec)) {
            return Optional.empty();
        }

        Path privateHome;
        try {
            privateHome = Files.createTempDirectory("hensu-mcp-" + spec.name() + "-");
        } catch (IOException e) {
            operator.accept(
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
                                sandbox.wrap(
                                        argv,
                                        spec.sandbox(),
                                        catalog.workingDirectory(),
                                        privateHome)
                        : UnaryOperator.identity();
        McpServerSpec.Stdio rooted = rootedAt(spec, privateHome);
        Path workingDirectory = catalog.workingDirectory();
        CompletableFuture<StdioMcpConnection> opening =
                CompletableFuture.supplyAsync(
                        () ->
                                StdioMcpConnection.open(
                                        rooted, workingDirectory, wrapper, hostEnvironment),
                        SPAWNER);
        try {
            return Optional.of(new McpRoute(rooted, opening.get(), !contained));
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof McpException cause)) {
                discard(privateHome);
                throw e.getCause() instanceof RuntimeException unchecked
                        ? unchecked
                        : new IllegalStateException(e.getCause());
            }
            String message =
                    "MCP server '"
                            + spec.name()
                            + "' did not start, its tools are not offered to agents: "
                            + cause.getMessage();
            // The stack trace goes to the logger, the sentence to the operator: a launch
            // failure is usually a wrong path or a missing binary, and the cause reads
            // better than the trace.
            logger.log(Level.FINE, message, cause);
            operator.accept(message);
            discard(privateHome);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The launch finishes without us; whatever it produces is closed, not leaked.
            privateHomes.remove(privateHome);
            opening.whenComplete(
                    (connection, _) -> {
                        if (connection != null) {
                            connection.close();
                        }
                        deleteRecursively(privateHome);
                    });
            operator.accept(
                    "MCP server '"
                            + spec.name()
                            + "' did not start: the run was interrupted while it launched");
            return Optional.empty();
        }
    }

    /// Removes the private home of a server that has been stopped.
    ///
    /// @param rooted the declaration the server was launched with, as its route
    ///     carries it, not null
    void release(McpServerSpec.Stdio rooted) {
        String home = rooted.env().get("HOME");
        if (home != null) {
            discard(Path.of(home));
        }
    }

    /// Removes every private home this launcher created.
    @Override
    public void close() {
        privateHomes.forEach(StdioServerLauncher::deleteRecursively);
        privateHomes.clear();
    }

    private void discard(Path privateHome) {
        if (privateHomes.remove(privateHome)) {
            deleteRecursively(privateHome);
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
    /// declared-versus-available diff names the tools, and the notice below names why.
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
                                    "no sandbox backend: " + sandbox.unavailabilityReason(),
                                    "no working sandbox backend is available, so approving starts"
                                            + " this server uncontained for this run only."));
        }
        if (outcome == ApprovalOutcome.APPROVED) {
            operator.accept(
                    "MCP server '"
                            + spec.name()
                            + "' is starting without containment: a reviewer approved it ("
                            + sandbox.unavailabilityReason()
                            + ")");
            return true;
        }
        operator.accept(
                "MCP server '"
                        + spec.name()
                        + "' is skipped because no sandbox backend is available ("
                        + sandbox.unavailabilityReason()
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
}
