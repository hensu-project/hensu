package io.hensu.server.persistence;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/// Manages distributed recovery leases for workflow executions.
///
/// Each server node holds a lease on the executions it is currently running by
/// periodically bumping `last_heartbeat_at` in {@code runtime.execution_states}.
/// A recovery sweeper on any surviving node can detect stale leases and
/// atomically claim the orphaned executions for re-execution.
///
/// ### Lease Lifecycle
///
/// ```
/// +——————————————————————————————————————————————————————————————————————+
/// │  startExecution() → save("checkpoint") → server_node_id set          │
/// │       │                                                              │
/// │       ↓          every 30 s                                          │
/// │  updateHeartbeats() ————————> last_heartbeat_at = NOW()              │
/// │       │                                                              │
/// │  node crashes                                                        │
/// │       │                   after stale threshold                      │
/// │       ↓                                                              │
/// │  claimStaleExecutions() ———> new node claims orphaned rows           │
/// │       │                                                              │
/// │       ↓                                                              │
/// │  resumeExecution() → save("completed") → server_node_id = NULL       │
/// +——————————————————————————————————————————————————————————————————————+
/// ```
///
/// ### Concurrency Safety
///
/// `claimStaleExecutions` executes an `UPDATE … WHERE last_heartbeat_at < threshold
/// RETURNING …`. Under PostgreSQL's default `READ COMMITTED` isolation, if two
/// sweepers race on the same stale row, the second UPDATE re-evaluates the
/// `WHERE` clause against the committed row — which now has a fresh
/// `last_heartbeat_at` — and silently skips it. No application-level locking
/// is required.
///
/// @implNote Thread-safe. Each SQL call acquires its own connection from the
/// Agroal pool via {@link JdbcSupport}. Safe to call from virtual threads.
///
/// @see JdbcWorkflowStateRepository
/// @see io.hensu.server.workflow.ExecutionHeartbeatJob
/// @see io.hensu.server.workflow.WorkflowRecoveryJob
@ApplicationScoped
public class ExecutionLeaseManager {

    private static final Logger LOG = Logger.getLogger(ExecutionLeaseManager.class);

    // --- SQL constants ---

    static final String SQL_UPDATE_HEARTBEATS =
            "UPDATE runtime.execution_states SET last_heartbeat_at = NOW() WHERE server_node_id ="
                    + " ?";

    /// Uses PostgreSQL's UPDATE … RETURNING for single-pass atomic claiming.
    /// Executed via {@link JdbcSupport#queryList} which calls {@code executeQuery()},
    /// compatible with DML RETURNING in the PostgreSQL JDBC driver.
    static final String SQL_CLAIM_STALE =
            """
            UPDATE runtime.execution_states
               SET server_node_id    = ?,
                   last_heartbeat_at = NOW()
             WHERE server_node_id    IS NOT NULL
               AND last_heartbeat_at < ?
             RETURNING tenant_id, execution_id
            """;

    // --- CDI-injected fields ---

    @Inject Config config;

    @Inject Instance<DataSource> dataSourceInstance;

    @ConfigProperty(name = "hensu.node.id")
    Optional<String> configuredNodeId;

    // --- Instance fields ---

    private String serverNodeId;
    private JdbcSupport jdbc;
    private boolean active;

    /// CDI no-arg constructor required by ArC for field injection.
    public ExecutionLeaseManager() {}

    /// Package-private constructor for unit tests — bypasses CDI lifecycle.
    ///
    /// @param jdbc the JDBC helper to use, not null
    /// @param serverNodeId the node identifier to assign, not null
    ExecutionLeaseManager(JdbcSupport jdbc, String serverNodeId) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.active = true;
    }

    /// Initializes the server node identity and JDBC connection.
    ///
    /// Generates a random UUID if {@code hensu.node.id} is not configured.
    /// Marks the manager inactive when the datasource is disabled
    /// (e.g., in the {@code inmem} test profile).
    @PostConstruct
    void init() {
        serverNodeId =
                configuredNodeId
                        .filter(s -> !s.isBlank())
                        .orElseGet(() -> UUID.randomUUID().toString());

        boolean dsActive =
                config.getOptionalValue("quarkus.datasource.active", Boolean.class).orElse(true);
        active = dsActive && dataSourceInstance.isResolvable();

        if (active) {
            jdbc = new JdbcSupport(dataSourceInstance.get());
        }

        LOG.infov(
                "Execution lease manager initialized: nodeId={0}, active={1}",
                serverNodeId, active);
    }

    /// Returns the unique identifier for this server instance.
    ///
    /// @return the node UUID assigned at startup, never null
    public String getServerNodeId() {
        return serverNodeId;
    }

    /// Returns whether lease operations are available.
    ///
    /// Returns {@code false} when the datasource is inactive (e.g., the
    /// {@code inmem} test profile). Callers must check before invoking
    /// lease operations.
    ///
    /// @return {@code true} if JDBC is available and leasing is enabled
    public boolean isActive() {
        return active;
    }

    /// Bumps `last_heartbeat_at` for all executions owned by this node.
    ///
    /// Executes an unconditional bulk UPDATE — every row with
    /// {@code server_node_id = thisNode} is refreshed. No-op when inactive.
    ///
    /// @apiNote **Side effects**: modifies `last_heartbeat_at` for all active
    /// leases held by this node.
    public void updateHeartbeats() {
        if (!active) return;
        jdbc.update(
                SQL_UPDATE_HEARTBEATS,
                ps -> ps.setString(1, serverNodeId),
                "Failed to update heartbeats for node: " + serverNodeId);
        LOG.debugv("Heartbeat updated for node: {0}", serverNodeId);
    }

    /// Atomically claims all executions whose heartbeat is older than the given threshold.
    ///
    /// Uses a single `UPDATE … RETURNING` statement. Under PostgreSQL's default
    /// `READ COMMITTED` isolation, two concurrent sweepers cannot both claim the
    /// same row — the second re-checks the WHERE clause against the committed row
    /// and finds a fresh `last_heartbeat_at`.
    ///
    /// Returns an empty list when inactive.
    ///
    /// ### Contracts
    /// - **Precondition**: `threshold` is in the past
    /// - **Postcondition**: returned rows have `server_node_id = this.serverNodeId`
    ///
    /// @param threshold executions with `last_heartbeat_at` older than this are claimed, not null
    /// @return list of claimed execution references, may be empty, never null
    public List<ExecutionRef> claimStaleExecutions(Instant threshold) {
        Objects.requireNonNull(threshold, "threshold must not be null");
        if (!active) return List.of();

        return jdbc.queryList(
                SQL_CLAIM_STALE,
                ps -> {
                    ps.setString(1, serverNodeId);
                    ps.setObject(2, OffsetDateTime.ofInstant(threshold, ZoneOffset.UTC));
                },
                rs -> new ExecutionRef(rs.getString("tenant_id"), rs.getString("execution_id")),
                "Failed to claim stale executions for node: " + serverNodeId);
    }

    // --- Types ---

    /// A tenant-scoped reference to a workflow execution.
    ///
    /// @param tenantId the tenant that owns the execution, never null
    /// @param executionId the execution identifier, never null
    public record ExecutionRef(String tenantId, String executionId) {}
}
