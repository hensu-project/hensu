package io.hensu.server.persistence;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
/// ### Session Affinity
///
/// A tool call is answered over the tenant's MCP session, which lives in the
/// memory of whichever node the tenant's relay connected to. This class also
/// owns `runtime.mcp_sessions`, which records that node, so that claiming can
/// keep an execution's lease and its tenant's session on the same node:
///
/// - a node does not claim an execution whose tenant holds a **fresh** session
///   on another node – fresh meaning refreshed within the stale threshold
/// - a session row older than the threshold counts as no session; it blocks
///   nothing, and the node that claims past it deletes it
/// - session rows are refreshed by the same heartbeat as leases, so a dead node's
///   session and leases go stale together and recovery is never blocked by a
///   row nobody will clear
///
/// ### Runs on This Node
///
/// The write fence in {@link JdbcWorkflowStateRepository} checks which node owns
/// a row, not which run, so two runs of one execution on the same node would
/// both pass it. This class therefore tracks the executions with a live run
/// here – registered by {@link #beginRun} or a successful {@link #tryClaim} –
/// and never claims or re-claims one of them: a heartbeat that lapsed while the
/// run kept going does not hand its row to this node's own sweeper.
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

    /// Refreshes every lease this node holds except those it relinquished, whose
    /// heartbeat is pinned to the epoch so another node's sweeper can claim them.
    static final String SQL_UPDATE_HEARTBEATS =
            """
            UPDATE runtime.execution_states
               SET last_heartbeat_at = NOW()
             WHERE server_node_id = ?
               AND last_heartbeat_at IS DISTINCT FROM TIMESTAMPTZ 'epoch'
            """;

    /// Claims every stale lease whose tenant has no fresh session on another node,
    /// and in the same statement deletes the stale foreign session rows of the
    /// tenants it claimed, so a dead node's session row never blocks recovery.
    ///
    /// Executions still running on this node are skipped even when their row is
    /// stale: a lapsed heartbeat – the database unreachable past the threshold –
    /// does not stop the run, and claiming its row would resume a second copy
    /// beside it that the node-scoped write fence cannot tell apart.
    ///
    /// Uses PostgreSQL's UPDATE … RETURNING inside a data-modifying CTE for
    /// single-pass atomic claiming. Executed via {@link JdbcSupport#queryList},
    /// which calls {@code executeQuery()}.
    static final String SQL_CLAIM_STALE =
            """
            WITH running (tenant_id, execution_id) AS (
                SELECT * FROM unnest(?::text[], ?::text[])
            ), claimed AS (
                UPDATE runtime.execution_states e
                   SET server_node_id    = ?,
                       last_heartbeat_at = NOW()
                 WHERE e.server_node_id IS NOT NULL
                   AND e.last_heartbeat_at < ?
                   AND NOT EXISTS (
                           SELECT 1
                             FROM running r
                            WHERE r.tenant_id    = e.tenant_id
                              AND r.execution_id = e.execution_id)
                   AND NOT EXISTS (
                           SELECT 1
                             FROM runtime.mcp_sessions s
                            WHERE s.tenant_id          = e.tenant_id
                              AND s.server_node_id    <> ?
                              AND s.last_heartbeat_at >= NOW() - make_interval(secs => ?))
                 RETURNING e.tenant_id, e.execution_id
            ), purged AS (
                DELETE FROM runtime.mcp_sessions s
                 USING (SELECT DISTINCT tenant_id FROM claimed) c
                 WHERE s.tenant_id         = c.tenant_id
                   AND s.server_node_id   <> ?
                   AND s.last_heartbeat_at < NOW() - make_interval(secs => ?)
            )
            SELECT tenant_id, execution_id FROM claimed
            """;

    /// Conditional claim of a single execution. Succeeds when the row is unowned
    /// (`server_node_id IS NULL`) or already owned by this node (re-entrant for the
    /// recovery → resume hand-off), and the tenant holds no fresh session on another
    /// node. Purges the tenant's stale foreign session row when it claims, and
    /// reports whether the row exists at all, so a refusal can tell a missing
    /// execution from one held elsewhere.
    static final String SQL_TRY_CLAIM =
            """
            WITH claimed AS (
                UPDATE runtime.execution_states e
                   SET server_node_id    = ?,
                       last_heartbeat_at = NOW()
                 WHERE e.tenant_id    = ?
                   AND e.execution_id = ?
                   AND (e.server_node_id IS NULL OR e.server_node_id = ?)
                   AND NOT EXISTS (
                           SELECT 1
                             FROM runtime.mcp_sessions s
                            WHERE s.tenant_id          = e.tenant_id
                              AND s.server_node_id    <> ?
                              AND s.last_heartbeat_at >= NOW() - make_interval(secs => ?))
                 RETURNING e.tenant_id
            ), purged AS (
                DELETE FROM runtime.mcp_sessions s
                 USING claimed c
                 WHERE s.tenant_id         = c.tenant_id
                   AND s.server_node_id   <> ?
                   AND s.last_heartbeat_at < NOW() - make_interval(secs => ?)
            )
            SELECT (SELECT COUNT(*) FROM claimed) AS claimed,
                   EXISTS (
                       SELECT 1
                         FROM runtime.execution_states
                        WHERE tenant_id    = ?
                          AND execution_id = ?) AS present
            """;

    /// Releases a lease held by this node. No-op if another node owns the row.
    static final String SQL_RELEASE =
            """
            UPDATE runtime.execution_states
               SET server_node_id = NULL
             WHERE tenant_id      = ?
               AND execution_id   = ?
               AND server_node_id = ?
            """;

    /// Hands a lease this node holds back to recovery. The owner is kept – a NULL
    /// owner would read as safely paused and nobody would ever resume the execution
    /// – and the heartbeat is pinned to the epoch, which makes the row stale at once
    /// and which {@link #SQL_UPDATE_HEARTBEATS} never refreshes.
    static final String SQL_RELINQUISH =
            """
            UPDATE runtime.execution_states
               SET last_heartbeat_at = TIMESTAMPTZ 'epoch'
             WHERE tenant_id      = ?
               AND execution_id   = ?
               AND server_node_id = ?
            """;

    /// Records this node as the holder of a tenant's session. Takes the row from
    /// any other node: a connection here supersedes one held elsewhere.
    static final String SQL_PUBLISH_SESSION =
            """
            INSERT INTO runtime.mcp_sessions
                   (tenant_id, server_node_id, connected_at, last_heartbeat_at)
            VALUES (?, ?, NOW(), NOW())
            ON CONFLICT (tenant_id) DO UPDATE
               SET server_node_id    = EXCLUDED.server_node_id,
                   connected_at      = EXCLUDED.connected_at,
                   last_heartbeat_at = EXCLUDED.last_heartbeat_at
            """;

    /// Deletes a tenant's session row only while this node still holds it, so a
    /// displaced stream here cannot delete the row of the node that superseded it.
    static final String SQL_CLEAR_SESSION =
            """
            DELETE FROM runtime.mcp_sessions
             WHERE tenant_id      = ?
               AND server_node_id = ?
            """;

    /// Refreshes this node's rows for the tenants it lists, re-inserting any that
    /// are missing, and never takes a row another node holds. Returns the tenants
    /// it refreshed or inserted; a listed tenant missing from the result has its
    /// row held by another node.
    static final String SQL_REFRESH_SESSIONS =
            """
            INSERT INTO runtime.mcp_sessions
                   (tenant_id, server_node_id, connected_at, last_heartbeat_at)
            SELECT t, ?, NOW(), NOW()
              FROM unnest(?::text[]) AS t
            ON CONFLICT (tenant_id) DO UPDATE
               SET last_heartbeat_at = NOW()
             WHERE mcp_sessions.server_node_id = EXCLUDED.server_node_id
            RETURNING tenant_id
            """;

    /// The node holding a tenant's fresh session, if any.
    static final String SQL_SESSION_OWNER =
            """
            SELECT server_node_id
              FROM runtime.mcp_sessions
             WHERE tenant_id          = ?
               AND last_heartbeat_at >= NOW() - make_interval(secs => ?)
            """;

    // --- CDI-injected fields ---

    @Inject Config config;

    @Inject Instance<DataSource> dataSourceInstance;

    @ConfigProperty(name = "hensu.node.id")
    Optional<String> configuredNodeId;

    @ConfigProperty(name = "hensu.lease.stale-threshold", defaultValue = "90s")
    Duration staleThreshold;

    // --- Instance fields ---

    private String serverNodeId;
    private JdbcSupport jdbc;
    private boolean active;

    /// Executions this node claimed through {@link #claimStaleExecutions} and has
    /// not yet handed to {@link #tryClaim}. Only these may be relinquished when
    /// their tenant's session turns out to be elsewhere: any other lease this node
    /// holds belongs to an execution running here, and giving it back would let a
    /// second node resume the same execution concurrently.
    private final Set<ExecutionRef> pendingHandOffs = ConcurrentHashMap.newKeySet();

    /// Executions with a live run on this node: started through {@link #beginRun},
    /// or resumed after {@link #tryClaim} returned {@link ClaimOutcome.Claimed}.
    /// The write fence is scoped to the node, not to the run, so two runs of one
    /// execution here would both pass it; this set is what keeps there from being
    /// a second.
    private final Set<ExecutionRef> running = ConcurrentHashMap.newKeySet();

    /// CDI no-arg constructor required by ArC for field injection.
    public ExecutionLeaseManager() {}

    /// Package-private constructor for unit tests — bypasses CDI lifecycle.
    ///
    /// @param jdbc the JDBC helper to use, not null
    /// @param serverNodeId the node identifier to assign, not null
    /// @param staleThreshold age past which a lease or session row is dead, not null
    ExecutionLeaseManager(JdbcSupport jdbc, String serverNodeId, Duration staleThreshold) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.staleThreshold =
                Objects.requireNonNull(staleThreshold, "staleThreshold must not be null");
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

    /// Returns how old a heartbeat may be before its lease or session counts as dead.
    ///
    /// Configured by `hensu.lease.stale-threshold`; one value governs both, so a
    /// session and the leases it serves go stale together.
    ///
    /// @return the stale threshold, never null
    public Duration getStaleThreshold() {
        return staleThreshold;
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

    /// Attempts to claim a single execution for this node before resuming it.
    ///
    /// Succeeds when the row is unowned or already owned by this node – the
    /// recovery sweeper claims via {@link #claimStaleExecutions} and then drives
    /// `resumeExecution` against the same row, so re-entry must succeed – and the
    /// tenant holds no fresh MCP session on another node.
    ///
    /// A successful claim registers the execution as running on this node until
    /// {@link #release}. An execution already running here is refused without
    /// touching its row, so a second resume – or a recovery that raced one – cannot
    /// start a copy beside the live run.
    ///
    /// When the claim is refused for an execution that reached here through the
    /// recovery hand-off, this node gives the lease back to recovery (see
    /// {@link #SQL_RELINQUISH}) whatever the reason, so that the next sweep – on
    /// the session-holding node, or on this one if the session has since come
    /// back – picks it up. Keeping it would leave a row this node heartbeats but
    /// never runs. A lease held for an execution running on this node is never
    /// given back.
    ///
    /// Returns {@link ClaimOutcome.Claimed} unconditionally when leasing is
    /// inactive (e.g., the `inmem` profile) so single-node and test setups bypass
    /// the check.
    ///
    /// @apiNote Issues blocking JDBC calls. Call from a Virtual Thread or worker
    /// thread, never from the event loop.
    ///
    /// @param tenantId    the tenant owning the execution, not null
    /// @param executionId the execution identifier, not null
    /// @return the claim outcome, never null
    public ClaimOutcome tryClaim(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        if (!active) return new ClaimOutcome.Claimed();

        ExecutionRef ref = new ExecutionRef(tenantId, executionId);
        boolean handOff = pendingHandOffs.remove(ref);
        if (!running.add(ref)) {
            return new ClaimOutcome.AlreadyRunning();
        }

        ClaimRow row;
        try {
            row = claimRow(tenantId, executionId);
        } catch (RuntimeException e) {
            running.remove(ref);
            throw e;
        }
        if (row.claimed()) {
            return new ClaimOutcome.Claimed();
        }
        running.remove(ref);
        if (!row.present()) {
            return new ClaimOutcome.NotFound();
        }

        Optional<String> sessionNode = sessionElsewhere(tenantId);
        if (handOff) {
            relinquish(tenantId, executionId);
            LOG.infov(
                    "Relinquished recovered execution {0}: {1}",
                    executionId,
                    sessionNode
                            .map(node -> describeSessionElsewhere(tenantId, node))
                            .orElse("its claim was refused"));
        }
        return sessionNode
                .<ClaimOutcome>map(ClaimOutcome.SessionElsewhere::new)
                .orElseGet(ClaimOutcome.LeasedElsewhere::new);
    }

    /// Registers a new execution as running on this node.
    ///
    /// A new execution has no row to claim until its first checkpoint, so the
    /// start path registers here instead of through {@link #tryClaim}. Pair with
    /// {@link #endRun} in a `finally`. No-op when inactive.
    ///
    /// @param tenantId    the tenant owning the execution, not null
    /// @param executionId the execution identifier, not null
    public void beginRun(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        if (!active) return;
        running.add(new ExecutionRef(tenantId, executionId));
    }

    /// Unregisters an execution started through {@link #beginRun}.
    ///
    /// Leaves the row alone: a run ends by writing its terminal state, which
    /// already clears the owner. No-op when inactive.
    ///
    /// @param tenantId    the tenant owning the execution, not null
    /// @param executionId the execution identifier, not null
    public void endRun(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        if (!active) return;
        running.remove(new ExecutionRef(tenantId, executionId));
    }

    /// Ends this node's run of an execution and releases the lease it holds.
    ///
    /// Call from a `finally` after a successful `tryClaim` only: a refused claim
    /// may be refused because another run is live here, and releasing would
    /// unregister that run. The WHERE clause filters by this node id, so a
    /// release after another node took over leaves the row alone.
    ///
    /// @param tenantId    the tenant owning the execution, not null
    /// @param executionId the execution identifier, not null
    public void release(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        if (!active) return;
        running.remove(new ExecutionRef(tenantId, executionId));
        jdbc.update(
                SQL_RELEASE,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, executionId);
                    ps.setString(3, serverNodeId);
                },
                "Failed to release execution: " + executionId);
    }

    /// Atomically claims all executions whose heartbeat is older than the given
    /// threshold and whose tenant holds no fresh MCP session on another node,
    /// except those still running on this node.
    ///
    /// Uses a single `UPDATE … RETURNING` statement. Under PostgreSQL's default
    /// `READ COMMITTED` isolation, two concurrent sweepers cannot both claim the
    /// same row — the second re-checks the WHERE clause against the committed row
    /// and finds a fresh `last_heartbeat_at`. The same statement deletes the stale
    /// session rows other nodes left behind for the tenants it claimed.
    ///
    /// Returns an empty list when inactive.
    ///
    /// ### Contracts
    /// - **Precondition**: `threshold` is in the past
    /// - **Postcondition**: returned rows have `server_node_id = this.serverNodeId`
    ///   and are remembered as recovery hand-offs for {@link #tryClaim}
    ///
    /// @param threshold executions with `last_heartbeat_at` older than this are claimed, not null
    /// @return list of claimed execution references, may be empty, never null
    public List<ExecutionRef> claimStaleExecutions(Instant threshold) {
        Objects.requireNonNull(threshold, "threshold must not be null");
        if (!active) return List.of();

        double staleSeconds = staleSeconds();
        List<ExecutionRef> runningHere = List.copyOf(running);
        Object[] runningTenants = runningHere.stream().map(ExecutionRef::tenantId).toArray();
        Object[] runningExecutions = runningHere.stream().map(ExecutionRef::executionId).toArray();
        List<ExecutionRef> claimed =
                jdbc.queryList(
                        SQL_CLAIM_STALE,
                        ps -> {
                            ps.setArray(
                                    1, ps.getConnection().createArrayOf("text", runningTenants));
                            ps.setArray(
                                    2, ps.getConnection().createArrayOf("text", runningExecutions));
                            ps.setString(3, serverNodeId);
                            ps.setObject(4, OffsetDateTime.ofInstant(threshold, ZoneOffset.UTC));
                            ps.setString(5, serverNodeId);
                            ps.setDouble(6, staleSeconds);
                            ps.setString(7, serverNodeId);
                            ps.setDouble(8, staleSeconds);
                        },
                        rs ->
                                new ExecutionRef(
                                        rs.getString("tenant_id"), rs.getString("execution_id")),
                        "Failed to claim stale executions for node: " + serverNodeId);
        pendingHandOffs.addAll(claimed);
        return claimed;
    }

    /// Forgets a recovery hand-off that {@link #tryClaim} did not consume.
    ///
    /// {@link #tryClaim} consumes the hand-off on the normal path; this covers a
    /// recovery that ended before reaching it. A hand-off left behind would let a
    /// later `tryClaim` on the same execution – by then possibly running here –
    /// give its lease back. No-op when the hand-off was already consumed.
    ///
    /// @param ref the execution claimed by {@link #claimStaleExecutions}, not null
    public void discardHandOff(ExecutionRef ref) {
        pendingHandOffs.remove(Objects.requireNonNull(ref, "ref must not be null"));
    }

    /// Records this node as the holder of a tenant's MCP session.
    ///
    /// Takes the row from any other node, since a connection here supersedes one
    /// held elsewhere. No-op when inactive.
    ///
    /// @apiNote Issues a blocking JDBC call. Call from a Virtual Thread or worker
    /// thread, never from the event loop.
    ///
    /// @param tenantId the tenant whose relay connected to this node, not null
    public void publishSession(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (!active) return;
        jdbc.update(
                SQL_PUBLISH_SESSION,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, serverNodeId);
                },
                "Failed to publish MCP session for tenant: " + tenantId);
    }

    /// Deletes a tenant's session row while this node still holds it.
    ///
    /// A row another node has since taken is left alone. No-op when inactive.
    ///
    /// @apiNote Issues a blocking JDBC call. Call from a Virtual Thread or worker
    /// thread, never from the event loop.
    ///
    /// @param tenantId the tenant whose session on this node ended, not null
    public void clearSession(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (!active) return;
        jdbc.update(
                SQL_CLEAR_SESSION,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, serverNodeId);
                },
                "Failed to clear MCP session for tenant: " + tenantId);
    }

    /// Refreshes the session rows of the tenants connected to this node.
    ///
    /// Re-inserts a row that is missing – a publish that failed, or a row another
    /// node purged as stale – so a live session's row heals within one heartbeat
    /// interval. Never takes a row another node holds: a half-open stream here
    /// cannot win back a session that moved, and the tenants whose row is held
    /// elsewhere are returned so the caller can retire those streams. A tenant
    /// left out of the list is not refreshed, so its row ages out at the stale
    /// threshold even if its delete failed. No-op when inactive or when
    /// `tenantIds` is empty.
    ///
    /// @apiNote Issues a blocking JDBC call. Call from a Virtual Thread or worker
    /// thread, never from the event loop.
    ///
    /// @param tenantIds the tenants with a live session on this node, not null
    /// @return the listed tenants whose session row another node holds, never null
    public Set<String> refreshSessions(Collection<String> tenantIds) {
        Objects.requireNonNull(tenantIds, "tenantIds must not be null");
        if (!active || tenantIds.isEmpty()) return Set.of();
        Object[] tenants = tenantIds.toArray();
        List<String> refreshed =
                jdbc.queryList(
                        SQL_REFRESH_SESSIONS,
                        ps -> {
                            ps.setString(1, serverNodeId);
                            ps.setArray(2, ps.getConnection().createArrayOf("text", tenants));
                        },
                        rs -> rs.getString("tenant_id"),
                        "Failed to refresh MCP sessions for node: " + serverNodeId);
        Set<String> heldElsewhere = new HashSet<>(tenantIds);
        refreshed.forEach(heldElsewhere::remove);
        return heldElsewhere;
    }

    /// Returns the node holding a tenant's fresh MCP session.
    ///
    /// A row older than the stale threshold counts as no session. The result may
    /// name this node. Returns empty when inactive.
    ///
    /// @apiNote Issues a blocking JDBC call. Call from a Virtual Thread or worker
    /// thread, never from the event loop.
    ///
    /// @param tenantId the tenant to look up, not null
    /// @return the owning node id, or empty if the tenant has no fresh session
    public Optional<String> sessionOwner(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (!active) return Optional.empty();
        double staleSeconds = staleSeconds();
        return jdbc.queryOne(
                SQL_SESSION_OWNER,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setDouble(2, staleSeconds);
                },
                rs -> rs.getString("server_node_id"),
                "Failed to read MCP session owner for tenant: " + tenantId);
    }

    /// Returns the other node holding a tenant's fresh MCP session.
    ///
    /// This is the question every session-affinity check asks: the session is
    /// fresh and not here. Returns empty when inactive.
    ///
    /// @apiNote Issues a blocking JDBC call. Call from a Virtual Thread or worker
    /// thread, never from the event loop.
    ///
    /// @param tenantId the tenant to look up, not null
    /// @return the other node's id, or empty if the session is here or nowhere
    public Optional<String> sessionElsewhere(String tenantId) {
        return sessionOwner(tenantId).filter(node -> !node.equals(serverNodeId));
    }

    /// Describes a tenant whose MCP session is held by another node.
    ///
    /// The one wording every refusal and diagnosis built on
    /// {@link #sessionElsewhere} starts from; callers append what it means for
    /// them.
    ///
    /// @param tenantId the tenant, not null
    /// @param nodeId the node holding the session, not null
    /// @return the description, never null
    public static String describeSessionElsewhere(String tenantId, String nodeId) {
        return "Tenant '" + tenantId + "' has its MCP session on node " + nodeId;
    }

    private ClaimRow claimRow(String tenantId, String executionId) {
        double staleSeconds = staleSeconds();
        return jdbc.queryOne(
                        SQL_TRY_CLAIM,
                        ps -> {
                            ps.setString(1, serverNodeId);
                            ps.setString(2, tenantId);
                            ps.setString(3, executionId);
                            ps.setString(4, serverNodeId);
                            ps.setString(5, serverNodeId);
                            ps.setDouble(6, staleSeconds);
                            ps.setString(7, serverNodeId);
                            ps.setDouble(8, staleSeconds);
                            ps.setString(9, tenantId);
                            ps.setString(10, executionId);
                        },
                        rs -> new ClaimRow(rs.getLong("claimed") > 0, rs.getBoolean("present")),
                        "Failed to claim execution: " + executionId)
                .orElse(new ClaimRow(false, false));
    }

    private void relinquish(String tenantId, String executionId) {
        jdbc.update(
                SQL_RELINQUISH,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, executionId);
                    ps.setString(3, serverNodeId);
                },
                "Failed to relinquish execution: " + executionId);
    }

    private double staleSeconds() {
        return staleThreshold.toMillis() / 1000.0;
    }

    /// Result of {@link #SQL_TRY_CLAIM}: whether this node took the row, and
    /// whether the row exists at all.
    private record ClaimRow(boolean claimed, boolean present) {}

    // --- Types ---

    /// A tenant-scoped reference to a workflow execution.
    ///
    /// @param tenantId the tenant that owns the execution, never null
    /// @param executionId the execution identifier, never null
    public record ExecutionRef(String tenantId, String executionId) {}

    /// Outcome of {@link #tryClaim}.
    ///
    /// ### Permitted Subtypes
    /// - {@link Claimed} – this node now holds the lease
    /// - {@link AlreadyRunning} – a run of the execution is live on this node
    /// - {@link NotFound} – no such execution exists
    /// - {@link LeasedElsewhere} – another node holds the lease
    /// - {@link SessionElsewhere} – the tenant's MCP session is held by another
    ///   node, so this node must not run the execution
    public sealed interface ClaimOutcome {

        /// This node now holds the lease.
        record Claimed() implements ClaimOutcome {}

        /// A run of the execution is already live on this node.
        record AlreadyRunning() implements ClaimOutcome {}

        /// No such execution exists.
        record NotFound() implements ClaimOutcome {}

        /// Another node holds the lease.
        record LeasedElsewhere() implements ClaimOutcome {}

        /// The tenant's MCP session is held by another node.
        ///
        /// @param nodeId the node holding the session, not null
        record SessionElsewhere(String nodeId) implements ClaimOutcome {}
    }
}
