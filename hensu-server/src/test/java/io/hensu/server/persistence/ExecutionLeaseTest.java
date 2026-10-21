package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.server.persistence.ExecutionLeaseManager.ClaimOutcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Integration tests for {@link ExecutionLeaseManager} against a real PostgreSQL instance.
///
/// Each test targets a specific correctness property of the lease protocol:
/// - Orphaned rows are atomically claimed by the sweeper node
/// - Rows with fresh heartbeats are never claimed (guards live executions)
/// - Heartbeat UPDATE is node-scoped (guards crashed-node detection)
/// - Claiming is session-aware: an execution is not claimed away from the node
///   holding its tenant's fresh MCP session, and a stale session row blocks nothing
/// - Session rows are fenced by node: no node deletes or refreshes another's row
/// - A run live on a node is never claimed or re-claimed by that same node, which
///   the node-scoped write fence alone cannot prevent
class ExecutionLeaseTest extends JdbcRepositoryTestBase {

    private static final String NODE_A = "node-alpha";
    private static final String NODE_B = "node-beta";

    /// Owner of executions whose node crashed; no manager runs as this node.
    private static final String NODE_DEAD = "node-dead";

    private static final Duration STALE = Duration.ofSeconds(90);
    private static final Instant EPOCH = Instant.EPOCH;

    private JdbcWorkflowStateRepository nodeARepo;
    private JdbcWorkflowStateRepository deadNodeRepo;
    private ExecutionLeaseManager nodeAManager;
    private ExecutionLeaseManager nodeBManager;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcWorkflowRepository workflowRepo = new JdbcWorkflowRepository(dataSource);
        nodeARepo = new JdbcWorkflowStateRepository(dataSource, objectMapper, NODE_A);
        deadNodeRepo = new JdbcWorkflowStateRepository(dataSource, objectMapper, NODE_DEAD);
        nodeAManager = new ExecutionLeaseManager(new JdbcSupport(dataSource), NODE_A, STALE);
        nodeBManager = new ExecutionLeaseManager(new JdbcSupport(dataSource), NODE_B, STALE);

        nodeARepo.deleteAllForTenant(TENANT);
        workflowRepo.deleteAllForTenant(TENANT);
        workflowRepo.save(TENANT, buildWorkflow("wf-test"));
        execute("DELETE FROM runtime.mcp_sessions");
    }

    /// Orphaned execution (NODE_A crashed, heartbeat is 120 s old) is claimed by NODE_B.
    @Test
    void claimStaleExecutions_claimsOrphanedRow() throws SQLException {
        nodeARepo.save(TENANT, checkpoint("exec-orphan"));
        rewindHeartbeat("exec-orphan");

        Instant threshold = Instant.now().minus(90, ChronoUnit.SECONDS);
        List<ExecutionLeaseManager.ExecutionRef> claimed =
                nodeBManager.claimStaleExecutions(threshold);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().tenantId()).isEqualTo(TENANT);
        assertThat(claimed.getFirst().executionId()).isEqualTo("exec-orphan");
        // Verify DB row is now owned by NODE_B
        assertThat(readServerNodeId("exec-orphan")).isEqualTo(NODE_B);
    }

    /// Active execution (heartbeat just set by save()) must never be claimed.
    /// Catches an inverted threshold condition — a bug here would kill live executions.
    @Test
    void claimStaleExecutions_skipsRowWithFreshHeartbeat() {
        nodeARepo.save(TENANT, checkpoint("exec-active"));

        Instant threshold = Instant.now().minus(90, ChronoUnit.SECONDS);
        List<ExecutionLeaseManager.ExecutionRef> claimed =
                nodeBManager.claimStaleExecutions(threshold);

        assertThat(claimed).isEmpty();
    }

    /// Heartbeat UPDATE must only touch rows owned by the calling node.
    /// Catches a missing `WHERE server_node_id = ?` — without it, NODE_B would bump NODE_A's
    /// orphaned rows and prevent the sweeper from ever detecting NODE_A's crash.
    @Test
    void updateHeartbeats_onlyBumpsOwnRows() throws SQLException {
        // NODE_A owns exec-a; NODE_B owns exec-b — rewind both to simulate stale
        JdbcWorkflowStateRepository nodeBRepo =
                new JdbcWorkflowStateRepository(dataSource, objectMapper, NODE_B);
        nodeARepo.save(TENANT, checkpoint("exec-a"));
        nodeBRepo.save(TENANT, checkpoint("exec-b"));
        rewindHeartbeat("exec-a");
        rewindHeartbeat("exec-b");

        // NODE_B bumps its own rows
        nodeBManager.updateHeartbeats();

        Instant fresh = Instant.now().minus(30, ChronoUnit.SECONDS);

        // exec-b (NODE_B) must be bumped — heartbeat should be recent
        assertThat(readLastHeartbeat("exec-b")).isAfter(fresh);

        // exec-a (NODE_A) must remain stale — not touched by NODE_B
        assertThat(readLastHeartbeat("exec-a")).isBefore(fresh);
    }

    // --- Session-aware claiming ---

    /// Resuming a paused execution here while the tenant's relay is connected to
    /// NODE_B would run it without tools; the claim is refused and names NODE_B.
    @Test
    void tryClaim_refusesWhenTenantSessionIsFreshOnAnotherNode() throws SQLException {
        nodeARepo.save(TENANT, snapshot("exec-paused", "paused"));
        nodeBManager.publishSession(TENANT);

        ClaimOutcome outcome = nodeAManager.tryClaim(TENANT, "exec-paused");

        assertThat(outcome).isEqualTo(new ClaimOutcome.SessionElsewhere(NODE_B));
        assertThat(readServerNodeId("exec-paused")).isNull();
        assertThat(nodeBManager.tryClaim(TENANT, "exec-paused"))
                .isEqualTo(new ClaimOutcome.Claimed());
    }

    /// The sweeper must leave an orphan to the node holding its tenant's session,
    /// which is the node that can actually answer its tool calls.
    @Test
    void claimStaleExecutions_leavesOrphanToSessionHoldingNode() throws SQLException {
        deadNodeRepo.save(TENANT, checkpoint("exec-orphan"));
        rewindHeartbeat("exec-orphan");
        nodeAManager.publishSession(TENANT);

        Instant threshold = Instant.now().minus(STALE);
        assertThat(nodeBManager.claimStaleExecutions(threshold)).isEmpty();
        assertThat(readServerNodeId("exec-orphan")).isEqualTo(NODE_DEAD);

        assertThat(nodeAManager.claimStaleExecutions(threshold))
                .extracting(ExecutionLeaseManager.ExecutionRef::executionId)
                .containsExactly("exec-orphan");
    }

    /// A session row whose node died must not disable recovery: it is written
    /// already aged past the threshold, blocks nothing, and the claim deletes it.
    @Test
    void claimStaleExecutions_claimsPastStaleSessionRowAndDeletesIt() throws SQLException {
        deadNodeRepo.save(TENANT, checkpoint("exec-orphan"));
        rewindHeartbeat("exec-orphan");
        insertSession(TENANT, NODE_DEAD, 120);

        List<ExecutionLeaseManager.ExecutionRef> claimed =
                nodeBManager.claimStaleExecutions(Instant.now().minus(STALE));

        assertThat(claimed).hasSize(1);
        assertThat(readServerNodeId("exec-orphan")).isEqualTo(NODE_B);
        assertThat(readSessionNode(TENANT)).isNull();
    }

    @Test
    void tryClaim_claimsPastStaleSessionRowAndDeletesIt() throws SQLException {
        nodeARepo.save(TENANT, snapshot("exec-paused", "paused"));
        insertSession(TENANT, NODE_B, 120);

        assertThat(nodeAManager.tryClaim(TENANT, "exec-paused"))
                .isEqualTo(new ClaimOutcome.Claimed());
        assertThat(readSessionNode(TENANT)).isNull();
    }

    /// Recovery claimed the orphan, then the tenant's session moved before the
    /// resume could claim it. The lease goes back to recovery – owner kept, so it
    /// still reads as orphaned rather than paused – this node's heartbeat does not
    /// revive it, and the session-holding node's sweeper takes it.
    @Test
    void tryClaim_relinquishesRecoveredExecutionWhenSessionMoved() throws SQLException {
        deadNodeRepo.save(TENANT, checkpoint("exec-orphan"));
        rewindHeartbeat("exec-orphan");
        Instant threshold = Instant.now().minus(STALE);
        assertThat(nodeAManager.claimStaleExecutions(threshold)).hasSize(1);

        nodeBManager.publishSession(TENANT);
        ClaimOutcome outcome = nodeAManager.tryClaim(TENANT, "exec-orphan");

        assertThat(outcome).isEqualTo(new ClaimOutcome.SessionElsewhere(NODE_B));
        assertThat(readServerNodeId("exec-orphan")).isEqualTo(NODE_A);
        assertThat(readLastHeartbeat("exec-orphan")).isEqualTo(EPOCH);

        nodeAManager.updateHeartbeats();
        assertThat(readLastHeartbeat("exec-orphan")).isEqualTo(EPOCH);

        assertThat(nodeBManager.claimStaleExecutions(Instant.now().minus(STALE)))
                .extracting(ExecutionLeaseManager.ExecutionRef::executionId)
                .containsExactly("exec-orphan");
        assertThat(readServerNodeId("exec-orphan")).isEqualTo(NODE_B);
    }

    /// An execution running here keeps its lease when its tenant's session moves:
    /// giving it back would let another node resume it while it still runs.
    @Test
    void tryClaim_neverRelinquishesExecutionRunningOnThisNode() throws SQLException {
        nodeAManager.beginRun(TENANT, "exec-running");
        nodeARepo.save(TENANT, checkpoint("exec-running"));
        nodeBManager.publishSession(TENANT);

        assertThat(nodeAManager.tryClaim(TENANT, "exec-running"))
                .isEqualTo(new ClaimOutcome.AlreadyRunning());

        assertThat(readServerNodeId("exec-running")).isEqualTo(NODE_A);
        assertThat(readLastHeartbeat("exec-running"))
                .isAfter(Instant.now().minus(30, ChronoUnit.SECONDS));
    }

    /// The fence is scoped to the node, so two runs here would both pass it. A
    /// second resume – or a recovery that raced one – is refused while the first
    /// run is live, and admitted once it has released.
    @Test
    void tryClaim_refusesSecondRunOnThisNodeUntilReleased() {
        nodeARepo.save(TENANT, snapshot("exec-paused", "paused"));

        assertThat(nodeAManager.tryClaim(TENANT, "exec-paused"))
                .isEqualTo(new ClaimOutcome.Claimed());
        assertThat(nodeAManager.tryClaim(TENANT, "exec-paused"))
                .isEqualTo(new ClaimOutcome.AlreadyRunning());

        nodeAManager.release(TENANT, "exec-paused");
        assertThat(nodeAManager.tryClaim(TENANT, "exec-paused"))
                .isEqualTo(new ClaimOutcome.Claimed());
    }

    /// A missing row used to read as "leased elsewhere", which the REST layer
    /// turned into a 409 instead of a 404.
    @Test
    void tryClaim_reportsMissingExecution() {
        assertThat(nodeAManager.tryClaim(TENANT, "exec-missing"))
                .isEqualTo(new ClaimOutcome.NotFound());
    }

    /// Recovery claimed the orphan and the resume's claim was refused for a fresh
    /// session on NODE_B, but by the time the owner was looked up the session was
    /// gone. The lease used to stay here – heartbeated by this node and run by
    /// nobody, never stale again. It goes back to recovery whatever the reason.
    @Test
    void tryClaim_relinquishesRecoveredExecutionWhenSessionVanishesBeforeLookup()
            throws SQLException {
        ExecutionLeaseManager sessionVanishes =
                new ExecutionLeaseManager(new JdbcSupport(dataSource), NODE_A, STALE) {
                    @Override
                    public Optional<String> sessionOwner(String tenantId) {
                        return Optional.empty();
                    }
                };
        deadNodeRepo.save(TENANT, checkpoint("exec-orphan"));
        rewindHeartbeat("exec-orphan");
        assertThat(sessionVanishes.claimStaleExecutions(Instant.now().minus(STALE))).hasSize(1);
        nodeBManager.publishSession(TENANT);

        assertThat(sessionVanishes.tryClaim(TENANT, "exec-orphan"))
                .isEqualTo(new ClaimOutcome.LeasedElsewhere());

        sessionVanishes.updateHeartbeats();
        assertThat(readLastHeartbeat("exec-orphan")).isEqualTo(EPOCH);
        assertThat(nodeBManager.claimStaleExecutions(Instant.now().minus(STALE)))
                .extracting(ExecutionLeaseManager.ExecutionRef::executionId)
                .containsExactly("exec-orphan");
    }

    /// The database was unreachable past the threshold, so this node's heartbeat
    /// lapsed while its run kept going. Its own sweeper must not claim the row and
    /// resume a second copy beside the live run: both would pass the node-scoped
    /// fence. Another node may claim it – the live run is then fenced off at its
    /// next checkpoint – and this node may once the run has ended.
    @Test
    void claimStaleExecutions_skipsExecutionStillRunningOnThisNode() throws SQLException {
        nodeAManager.beginRun(TENANT, "exec-live");
        nodeARepo.save(TENANT, checkpoint("exec-live"));
        rewindHeartbeat("exec-live");
        Instant threshold = Instant.now().minus(STALE);

        assertThat(nodeAManager.claimStaleExecutions(threshold)).isEmpty();
        assertThat(readServerNodeId("exec-live")).isEqualTo(NODE_A);

        nodeAManager.endRun(TENANT, "exec-live");
        assertThat(nodeAManager.claimStaleExecutions(threshold))
                .extracting(ExecutionLeaseManager.ExecutionRef::executionId)
                .containsExactly("exec-live");
    }

    @Test
    void claimStaleExecutions_letsAnotherNodeClaimExecutionRunningHere() throws SQLException {
        nodeAManager.beginRun(TENANT, "exec-live");
        nodeARepo.save(TENANT, checkpoint("exec-live"));
        rewindHeartbeat("exec-live");

        assertThat(nodeBManager.claimStaleExecutions(Instant.now().minus(STALE))).hasSize(1);
        assertThatThrownBy(() -> nodeARepo.save(TENANT, checkpoint("exec-live")))
                .isInstanceOf(LeaseLostException.class);
    }

    /// A recovery that ended before reaching `tryClaim` must not leave a hand-off
    /// behind: a later claim refused for the session would then give back the
    /// lease of an execution that may be running here.
    @Test
    void discardHandOff_preventsLaterRelinquish() throws SQLException {
        deadNodeRepo.save(TENANT, checkpoint("exec-orphan"));
        rewindHeartbeat("exec-orphan");
        List<ExecutionLeaseManager.ExecutionRef> claimed =
                nodeAManager.claimStaleExecutions(Instant.now().minus(STALE));

        nodeAManager.discardHandOff(claimed.getFirst());
        nodeBManager.publishSession(TENANT);

        assertThat(nodeAManager.tryClaim(TENANT, "exec-orphan"))
                .isEqualTo(new ClaimOutcome.SessionElsewhere(NODE_B));
        assertThat(readLastHeartbeat("exec-orphan")).isAfter(Instant.now().minus(STALE));
    }

    // --- Write fencing ---

    /// The zombie case: NODE_A stalled past the threshold, NODE_B claimed and
    /// resumed the execution, NODE_A wakes and checkpoints. Its write is refused
    /// and NODE_B's state survives.
    @Test
    void save_refusesWriteFromNodeThatLostTheLease() throws SQLException {
        nodeARepo.save(TENANT, checkpoint("exec-orphan"));
        rewindHeartbeat("exec-orphan");
        nodeBManager.claimStaleExecutions(Instant.now().minus(STALE));
        JdbcWorkflowStateRepository nodeBRepo =
                new JdbcWorkflowStateRepository(dataSource, objectMapper, NODE_B);
        nodeBRepo.save(TENANT, snapshot("exec-orphan", "checkpoint", "step-2"));

        assertThatThrownBy(() -> nodeARepo.save(TENANT, checkpoint("exec-orphan")))
                .isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> nodeARepo.save(TENANT, snapshot("exec-orphan", "failed")))
                .isInstanceOf(LeaseLostException.class);

        assertThat(readServerNodeId("exec-orphan")).isEqualTo(NODE_B);
        assertThat(nodeBRepo.findByExecutionId(TENANT, "exec-orphan"))
                .get()
                .extracting(HensuSnapshot::currentNodeId)
                .isEqualTo("step-2");
    }

    /// A row released by its terminal write is no node's to overwrite; a later
    /// writer must claim it first, as resume does.
    @Test
    void save_refusesWriteToUnownedRowUntilClaimed() throws SQLException {
        nodeARepo.save(TENANT, snapshot("exec-paused", "paused"));

        assertThatThrownBy(() -> nodeARepo.save(TENANT, checkpoint("exec-paused")))
                .isInstanceOf(LeaseLostException.class);

        assertThat(nodeAManager.tryClaim(TENANT, "exec-paused"))
                .isEqualTo(new ClaimOutcome.Claimed());
        nodeARepo.save(TENANT, checkpoint("exec-paused"));
        assertThat(readServerNodeId("exec-paused")).isEqualTo(NODE_A);
    }

    // --- Session rows ---

    /// A connection on another node supersedes this one; the displaced stream's
    /// late termination here must not delete the successor's row.
    @Test
    void clearSession_neverDeletesAnotherNodesRow() throws SQLException {
        nodeAManager.publishSession(TENANT);
        nodeBManager.publishSession(TENANT);
        assertThat(readSessionNode(TENANT)).isEqualTo(NODE_B);

        nodeAManager.clearSession(TENANT);
        assertThat(readSessionNode(TENANT)).isEqualTo(NODE_B);

        nodeBManager.clearSession(TENANT);
        assertThat(readSessionNode(TENANT)).isNull();
    }

    @Test
    void refreshSessions_refreshesOnlyListedTenants() throws SQLException {
        insertSession(TENANT, NODE_A, 60);
        insertSession(OTHER_TENANT, NODE_A, 60);

        nodeAManager.refreshSessions(List.of(TENANT));

        Instant fresh = Instant.now().minus(30, ChronoUnit.SECONDS);
        assertThat(readSessionHeartbeat(TENANT)).isAfter(fresh);
        assertThat(readSessionHeartbeat(OTHER_TENANT)).isBefore(fresh);
    }

    /// A publish that failed, or a row purged as stale, heals on the next refresh
    /// while the stream is still live here.
    @Test
    void refreshSessions_reinsertsMissingRowForListedTenant() throws SQLException {
        assertThat(nodeAManager.refreshSessions(List.of(TENANT))).isEmpty();

        assertThat(readSessionNode(TENANT)).isEqualTo(NODE_A);
        assertThat(nodeAManager.sessionOwner(TENANT)).contains(NODE_A);
    }

    /// A half-open stream left on this node after the session moved must not win
    /// the row back from the node that now holds it, and is reported so the
    /// caller can retire it.
    @Test
    void refreshSessions_neverTakesAnotherNodesRowAndReportsIt() throws SQLException {
        insertSession(TENANT, NODE_B, 60);
        insertSession(OTHER_TENANT, NODE_A, 60);

        assertThat(nodeAManager.refreshSessions(List.of(TENANT, OTHER_TENANT)))
                .containsExactly(TENANT);

        assertThat(readSessionNode(TENANT)).isEqualTo(NODE_B);
        assertThat(readSessionHeartbeat(TENANT))
                .isBefore(Instant.now().minus(30, ChronoUnit.SECONDS));
    }

    @Test
    void sessionOwner_ignoresStaleRow() throws SQLException {
        insertSession(TENANT, NODE_B, 120);

        assertThat(nodeAManager.sessionOwner(TENANT)).isEmpty();
    }

    /// A row naming this node with no local stream is a session about to be
    /// cleared, not a cross-node case; every affinity check relies on this.
    @Test
    void sessionElsewhere_ignoresThisNodesRow() {
        nodeAManager.publishSession(TENANT);

        assertThat(nodeAManager.sessionElsewhere(TENANT)).isEmpty();
        assertThat(nodeBManager.sessionElsewhere(TENANT)).contains(NODE_A);
    }

    // --- Helpers ---

    private static HensuSnapshot checkpoint(String executionId) {
        return snapshot(executionId, "checkpoint");
    }

    private static HensuSnapshot snapshot(String executionId, String reason) {
        return snapshot(executionId, reason, "step-1");
    }

    private static HensuSnapshot snapshot(String executionId, String reason, String nodeId) {
        return new HensuSnapshot(
                "wf-test",
                executionId,
                nodeId,
                Map.of(),
                Map.of(),
                new ExecutionHistory(),
                null,
                Instant.now(),
                reason);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /// Writes a session row whose heartbeat is already `ageSeconds` old.
    private static void insertSession(String tenantId, String nodeId, int ageSeconds)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "INSERT INTO runtime.mcp_sessions"
                                        + " (tenant_id, server_node_id, connected_at,"
                                        + " last_heartbeat_at)"
                                        + " VALUES (?, ?, NOW() - (? * INTERVAL '1 second'),"
                                        + " NOW() - (? * INTERVAL '1 second'))")) {
            ps.setString(1, tenantId);
            ps.setString(2, nodeId);
            ps.setInt(3, ageSeconds);
            ps.setInt(4, ageSeconds);
            ps.executeUpdate();
        }
    }

    private static String readSessionNode(String tenantId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT server_node_id FROM runtime.mcp_sessions"
                                        + " WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("server_node_id") : null;
            }
        }
    }

    private static Instant readSessionHeartbeat(String tenantId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT last_heartbeat_at FROM runtime.mcp_sessions"
                                        + " WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getObject("last_heartbeat_at", java.time.OffsetDateTime.class)
                        .toInstant();
            }
        }
    }

    /// Rewinds `last_heartbeat_at` by the given number of seconds to simulate a crashed node.
    private void rewindHeartbeat(String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "UPDATE runtime.execution_states"
                                        + " SET last_heartbeat_at ="
                                        + "     last_heartbeat_at - (? * INTERVAL '1 second')"
                                        + " WHERE tenant_id = ? AND execution_id = ?")) {
            ps.setInt(1, 120);
            ps.setString(2, JdbcRepositoryTestBase.TENANT);
            ps.setString(3, executionId);
            ps.executeUpdate();
        }
    }

    private String readServerNodeId(String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT server_node_id FROM runtime.execution_states"
                                        + " WHERE tenant_id = ? AND execution_id = ?")) {
            ps.setString(1, JdbcRepositoryTestBase.TENANT);
            ps.setString(2, executionId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString("server_node_id");
            }
        }
    }

    private Instant readLastHeartbeat(String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT last_heartbeat_at FROM runtime.execution_states"
                                        + " WHERE tenant_id = ? AND execution_id = ?")) {
            ps.setString(1, JdbcRepositoryTestBase.TENANT);
            ps.setString(2, executionId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                var odt = rs.getObject("last_heartbeat_at", java.time.OffsetDateTime.class);
                return odt != null ? odt.toInstant() : null;
            }
        }
    }
}
