package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Integration tests for {@link ExecutionLeaseManager} against a real PostgreSQL instance.
///
/// Each test targets a specific correctness property of the lease protocol:
/// - Orphaned rows are atomically claimed by the sweeper node
/// - Rows with fresh heartbeats are never claimed (guards live executions)
/// - Heartbeat UPDATE is node-scoped (guards crashed-node detection)
class ExecutionLeaseTest extends JdbcRepositoryTestBase {

    private static final String NODE_A = "node-alpha";
    private static final String NODE_B = "node-beta";

    private JdbcWorkflowStateRepository nodeARepo;
    private ExecutionLeaseManager nodeBManager;

    @BeforeEach
    void setUp() {
        JdbcWorkflowRepository workflowRepo = new JdbcWorkflowRepository(dataSource);
        nodeARepo = new JdbcWorkflowStateRepository(dataSource, objectMapper, NODE_A);
        nodeBManager = new ExecutionLeaseManager(new JdbcSupport(dataSource), NODE_B);

        nodeARepo.deleteAllForTenant(TENANT);
        workflowRepo.deleteAllForTenant(TENANT);
        workflowRepo.save(TENANT, buildWorkflow("wf-test"));
    }

    /// Orphaned execution (NODE_A crashed, heartbeat is 120 s old) is claimed by NODE_B.
    @Test
    void claimStaleExecutions_claimsOrphanedRow() throws SQLException {
        nodeARepo.save(TENANT, checkpoint("exec-orphan", "wf-test", "step-1"));
        rewindHeartbeat(TENANT, "exec-orphan", 120);

        Instant threshold = Instant.now().minus(90, ChronoUnit.SECONDS);
        List<ExecutionLeaseManager.ExecutionRef> claimed =
                nodeBManager.claimStaleExecutions(threshold);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().tenantId()).isEqualTo(TENANT);
        assertThat(claimed.getFirst().executionId()).isEqualTo("exec-orphan");
        // Verify DB row is now owned by NODE_B
        assertThat(readServerNodeId(TENANT, "exec-orphan")).isEqualTo(NODE_B);
    }

    /// Active execution (heartbeat just set by save()) must never be claimed.
    /// Catches an inverted threshold condition — a bug here would kill live executions.
    @Test
    void claimStaleExecutions_skipsRowWithFreshHeartbeat() {
        nodeARepo.save(TENANT, checkpoint("exec-active", "wf-test", "step-1"));

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
        nodeARepo.save(TENANT, checkpoint("exec-a", "wf-test", "step-1"));
        nodeBRepo.save(TENANT, checkpoint("exec-b", "wf-test", "step-1"));
        rewindHeartbeat(TENANT, "exec-a", 120);
        rewindHeartbeat(TENANT, "exec-b", 120);

        // NODE_B bumps its own rows
        nodeBManager.updateHeartbeats();

        Instant fresh = Instant.now().minus(30, ChronoUnit.SECONDS);

        // exec-b (NODE_B) must be bumped — heartbeat should be recent
        assertThat(readLastHeartbeat(TENANT, "exec-b")).isAfter(fresh);

        // exec-a (NODE_A) must remain stale — not touched by NODE_B
        assertThat(readLastHeartbeat(TENANT, "exec-a")).isBefore(fresh);
    }

    // --- Helpers ---

    private static HensuSnapshot checkpoint(
            String executionId, String workflowId, String currentNodeId) {
        return new HensuSnapshot(
                workflowId,
                executionId,
                currentNodeId,
                Map.of(),
                new ExecutionHistory(),
                null,
                Instant.now(),
                "checkpoint");
    }

    /// Rewinds `last_heartbeat_at` by the given number of seconds to simulate a crashed node.
    private void rewindHeartbeat(String tenantId, String executionId, int seconds)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "UPDATE runtime.execution_states"
                                        + " SET last_heartbeat_at ="
                                        + "     last_heartbeat_at - (? * INTERVAL '1 second')"
                                        + " WHERE tenant_id = ? AND execution_id = ?")) {
            ps.setInt(1, seconds);
            ps.setString(2, tenantId);
            ps.setString(3, executionId);
            ps.executeUpdate();
        }
    }

    private String readServerNodeId(String tenantId, String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT server_node_id FROM runtime.execution_states"
                                        + " WHERE tenant_id = ? AND execution_id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, executionId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString("server_node_id");
            }
        }
    }

    private Instant readLastHeartbeat(String tenantId, String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT last_heartbeat_at FROM runtime.execution_states"
                                        + " WHERE tenant_id = ? AND execution_id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, executionId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                var odt = rs.getObject("last_heartbeat_at", java.time.OffsetDateTime.class);
                return odt != null ? odt.toInstant() : null;
            }
        }
    }
}
