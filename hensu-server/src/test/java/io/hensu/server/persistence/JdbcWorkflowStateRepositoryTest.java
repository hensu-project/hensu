package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Integration tests for {@link JdbcWorkflowStateRepository} against a real PostgreSQL instance.
///
/// Verifies CRUD operations, UPSERT semantics, FK constraints, tenant isolation,
/// and lease column set/clear behavior.
/// A parent workflow is always saved first to satisfy the foreign key reference.
class JdbcWorkflowStateRepositoryTest extends JdbcRepositoryTestBase {

    static final String NODE_ID = "test-node";

    private JdbcWorkflowStateRepository stateRepo;

    @BeforeEach
    void setUp() {
        JdbcWorkflowRepository workflowRepo = new JdbcWorkflowRepository(dataSource);
        stateRepo = new JdbcWorkflowStateRepository(dataSource, objectMapper, NODE_ID);

        // FK: execution_states references workflows — delete states first
        stateRepo.deleteAllForTenant(TENANT);
        stateRepo.deleteAllForTenant(OTHER_TENANT);
        workflowRepo.deleteAllForTenant(TENANT);
        workflowRepo.deleteAllForTenant(OTHER_TENANT);

        // Save parent workflow so FK constraint is satisfied
        workflowRepo.save(TENANT, buildWorkflow("wf-parent"));
        workflowRepo.save(OTHER_TENANT, buildWorkflow("wf-parent"));
    }

    @Test
    void saveAndFindByExecutionId_roundTrip() {
        HensuSnapshot snapshot = makeSnapshot("exec-1", "wf-parent", "process");

        stateRepo.save(TENANT, snapshot);

        Optional<HensuSnapshot> found = stateRepo.findByExecutionId(TENANT, "exec-1");
        assertThat(found).isPresent();

        HensuSnapshot s = found.get();
        assertThat(s.executionId()).isEqualTo("exec-1");
        assertThat(s.workflowId()).isEqualTo("wf-parent");
        assertThat(s.currentNodeId()).isEqualTo("process");
        assertThat(s.checkpointReason()).isEqualTo("checkpoint");
        assertThat(s.context()).containsEntry("topic", "AI");
    }

    @Test
    void save_upsertOverwritesCheckpoint() {
        stateRepo.save(TENANT, makeSnapshot("exec-u", "wf-parent", "process"));

        HensuSnapshot updated =
                new HensuSnapshot(
                        "wf-parent",
                        "exec-u",
                        "done",
                        Map.of("topic", "ML"),
                        new ExecutionHistory(),
                        null,
                        Instant.now(),
                        "completed");

        stateRepo.save(TENANT, updated);

        Optional<HensuSnapshot> found = stateRepo.findByExecutionId(TENANT, "exec-u");
        assertThat(found).isPresent();
        assertThat(found.get().currentNodeId()).isEqualTo("done");
        assertThat(found.get().checkpointReason()).isEqualTo("completed");
        assertThat(found.get().context()).containsEntry("topic", "ML");
    }

    @Test
    void save_activeCheckpoint_setsLease() throws SQLException {
        stateRepo.save(TENANT, makeSnapshot("exec-lease", "wf-parent", "process"));

        LeaseColumns lease = readLeaseColumns(TENANT, "exec-lease");
        assertThat(lease.serverNodeId()).isEqualTo(NODE_ID);
        assertThat(lease.lastHeartbeatAt()).isNotNull();
    }

    @Test
    void save_completed_clearsLease() throws SQLException {
        stateRepo.save(TENANT, makeSnapshot("exec-done", "wf-parent", "process"));

        HensuSnapshot completed =
                new HensuSnapshot(
                        "wf-parent",
                        "exec-done",
                        null,
                        Map.of(),
                        new ExecutionHistory(),
                        null,
                        Instant.now(),
                        "completed");
        stateRepo.save(TENANT, completed);

        LeaseColumns lease = readLeaseColumns(TENANT, "exec-done");
        assertThat(lease.serverNodeId()).isNull();
        assertThat(lease.lastHeartbeatAt()).isNull();
    }

    @Test
    void save_paused_clearsLease() throws SQLException {
        stateRepo.save(TENANT, makeSnapshot("exec-paused", "wf-parent", "process"));

        HensuSnapshot paused =
                new HensuSnapshot(
                        "wf-parent",
                        "exec-paused",
                        "process",
                        Map.of(),
                        new ExecutionHistory(),
                        null,
                        Instant.now(),
                        "paused");
        stateRepo.save(TENANT, paused);

        LeaseColumns lease = readLeaseColumns(TENANT, "exec-paused");
        assertThat(lease.serverNodeId()).isNull();
        assertThat(lease.lastHeartbeatAt()).isNull();
    }

    @Test
    void findByExecutionId_returnsEmptyWhenNotFound() {
        assertThat(stateRepo.findByExecutionId(TENANT, "nonexistent")).isEmpty();
    }

    @Test
    void findPaused_returnsOnlyHumanPausedSnapshots() {
        // Truly paused for human review: server_node_id = NULL (cleared on "paused" save)
        HensuSnapshot paused =
                new HensuSnapshot(
                        "wf-parent",
                        "exec-paused",
                        "process",
                        Map.of(),
                        new ExecutionHistory(),
                        null,
                        Instant.now(),
                        "paused");
        stateRepo.save(TENANT, paused);

        // Active checkpoint: server_node_id is set — must NOT appear in findPaused()
        stateRepo.save(TENANT, makeSnapshot("exec-running", "wf-parent", "process"));

        // Completed: currentNodeId is null — excluded by current_node_id IS NOT NULL
        HensuSnapshot completed =
                new HensuSnapshot(
                        "wf-parent",
                        "exec-done",
                        null,
                        Map.of(),
                        new ExecutionHistory(),
                        null,
                        Instant.now(),
                        "completed");
        stateRepo.save(TENANT, completed);

        List<HensuSnapshot> found = stateRepo.findPaused(TENANT);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().executionId()).isEqualTo("exec-paused");
    }

    @Test
    void findByWorkflowId_returnsMatchingSnapshots() {
        stateRepo.save(TENANT, makeSnapshot("exec-a", "wf-parent", "process"));
        stateRepo.save(TENANT, makeSnapshot("exec-b", "wf-parent", "done"));

        List<HensuSnapshot> found = stateRepo.findByWorkflowId(TENANT, "wf-parent");
        assertThat(found).hasSize(2);
        assertThat(found)
                .extracting(HensuSnapshot::executionId)
                .containsExactlyInAnyOrder("exec-a", "exec-b");
    }

    @Test
    void delete_returnsTrueWhenDeleted() {
        stateRepo.save(TENANT, makeSnapshot("exec-del", "wf-parent", "process"));

        assertThat(stateRepo.delete(TENANT, "exec-del")).isTrue();
        assertThat(stateRepo.findByExecutionId(TENANT, "exec-del")).isEmpty();
    }

    @Test
    void delete_returnsFalseWhenNotFound() {
        assertThat(stateRepo.delete(TENANT, "exec-ghost")).isFalse();
    }

    @Test
    void deleteAllForTenant_removesAllAndReturnsCount() {
        stateRepo.save(TENANT, makeSnapshot("exec-1", "wf-parent", "process"));
        stateRepo.save(TENANT, makeSnapshot("exec-2", "wf-parent", "done"));

        int deleted = stateRepo.deleteAllForTenant(TENANT);
        assertThat(deleted).isEqualTo(2);
        assertThat(stateRepo.findByWorkflowId(TENANT, "wf-parent")).isEmpty();
    }

    @Test
    void tenantIsolation_dataNotVisibleAcrossTenants() {
        stateRepo.save(TENANT, makeSnapshot("exec-shared", "wf-parent", "process"));
        stateRepo.save(OTHER_TENANT, makeSnapshot("exec-shared", "wf-parent", "done"));

        assertThat(stateRepo.findByExecutionId(TENANT, "exec-shared").orElseThrow().currentNodeId())
                .isEqualTo("process");
        assertThat(
                        stateRepo
                                .findByExecutionId(OTHER_TENANT, "exec-shared")
                                .orElseThrow()
                                .currentNodeId())
                .isEqualTo("done");

        stateRepo.deleteAllForTenant(TENANT);
        assertThat(stateRepo.findByExecutionId(TENANT, "exec-shared")).isEmpty();
        assertThat(stateRepo.findByExecutionId(OTHER_TENANT, "exec-shared")).isPresent();
    }

    @Test
    void historySerializationRoundTrip() {
        ExecutionHistory history = new ExecutionHistory();
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("process")
                        .result(NodeResult.success("test output", Map.of()))
                        .build());

        HensuSnapshot snapshot =
                new HensuSnapshot(
                        "wf-parent",
                        "exec-hist",
                        "process",
                        Map.of("topic", "history"),
                        history,
                        null,
                        Instant.now(),
                        "checkpoint");

        stateRepo.save(TENANT, snapshot);

        HensuSnapshot restored = stateRepo.findByExecutionId(TENANT, "exec-hist").orElseThrow();
        assertThat(restored.history().getSteps()).hasSize(1);
        assertThat(restored.history().getSteps().getFirst().getNodeId()).isEqualTo("process");
        assertThat(restored.history().getSteps().getFirst().getResult().getStatus())
                .isEqualTo(ResultStatus.SUCCESS);
    }

    // --- Helpers ---

    /// Creates a minimal snapshot with default context and empty history.
    ///
    /// @param executionId unique execution identifier, not null
    /// @param workflowId parent workflow identifier (must exist in DB), not null
    /// @param currentNodeId the node where execution is paused, not null
    /// @return a valid snapshot with {@code checkpoint_reason = "checkpoint"}, never null
    private static HensuSnapshot makeSnapshot(
            String executionId, String workflowId, String currentNodeId) {
        return new HensuSnapshot(
                workflowId,
                executionId,
                currentNodeId,
                Map.of("topic", "AI"),
                new ExecutionHistory(),
                null,
                Instant.now(),
                "checkpoint");
    }

    /// Reads the raw lease columns directly from the DB for assertion.
    private LeaseColumns readLeaseColumns(String tenantId, String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT server_node_id, last_heartbeat_at"
                                        + " FROM hensu.execution_states"
                                        + " WHERE tenant_id = ? AND execution_id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return new LeaseColumns(
                        rs.getString("server_node_id"),
                        rs.getObject("last_heartbeat_at", java.time.OffsetDateTime.class));
            }
        }
    }

    private record LeaseColumns(String serverNodeId, java.time.OffsetDateTime lastHeartbeatAt) {}
}
