package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.workflow.Workflow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Integration tests for {@link JdbcWorkflowRepository} against a real PostgreSQL instance.
///
/// Verifies CRUD operations, UPSERT semantics, and tenant isolation.
class JdbcWorkflowRepositoryTest extends JdbcRepositoryTestBase {

    private JdbcWorkflowRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JdbcWorkflowRepository(dataSource);
        repo.deleteAllForTenant(TENANT);
        repo.deleteAllForTenant(OTHER_TENANT);
    }

    @Test
    void saveAndFindById_roundTrip() {
        Workflow workflow = buildWorkflow("wf-1");

        repo.save(TENANT, workflow);

        Optional<Workflow> found = repo.findById(TENANT, "wf-1");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("wf-1");
        assertThat(found.get().getVersion()).isEqualTo("1.0.0");
        assertThat(found.get().getStartNode()).isEqualTo("process");
        assertThat(found.get().getNodes()).containsKeys("process", "done");
        assertThat(found.get().getAgents()).containsKey("writer");
    }

    @Test
    void save_upsertOverwritesDefinition() {
        Workflow v1 = buildWorkflow("wf-upsert");
        repo.save(TENANT, v1);

        Workflow v2 =
                Workflow.builder()
                        .id("wf-upsert")
                        .version("2.0.0")
                        .agents(v1.getAgents())
                        .nodes(v1.getNodes())
                        .startNode(v1.getStartNode())
                        .build();
        repo.save(TENANT, v2);

        Optional<Workflow> found = repo.findById(TENANT, "wf-upsert");
        assertThat(found).isPresent();
        assertThat(found.get().getVersion()).isEqualTo("2.0.0");
        assertThat(repo.count(TENANT)).isEqualTo(1);
    }

    @Test
    void findById_returnsEmptyWhenNotFound() {
        assertThat(repo.findById(TENANT, "nonexistent")).isEmpty();
    }

    @Test
    void findAll_returnsOrderedByWorkflowId() {
        repo.save(TENANT, buildWorkflow("wf-c"));
        repo.save(TENANT, buildWorkflow("wf-a"));
        repo.save(TENANT, buildWorkflow("wf-b"));

        List<Workflow> all = repo.findAll(TENANT);
        assertThat(all).hasSize(3);
        assertThat(all).extracting(Workflow::getId).containsExactly("wf-a", "wf-b", "wf-c");
    }

    @Test
    void exists_returnsTrueWhenPresent() {
        repo.save(TENANT, buildWorkflow("wf-exists"));

        assertThat(repo.exists(TENANT, "wf-exists")).isTrue();
        assertThat(repo.exists(TENANT, "wf-ghost")).isFalse();
    }

    @Test
    void delete_softDeletesRowButKeepsItInTable() throws Exception {
        repo.save(TENANT, buildWorkflow("wf-del"));

        assertThat(repo.delete(TENANT, "wf-del")).isTrue();
        assertThat(repo.findById(TENANT, "wf-del")).isEmpty();

        // Row physically exists with non-null deleted_at
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT deleted_at FROM runtime.workflows WHERE tenant_id = ? AND workflow_id = ?")) {
            ps.setString(1, TENANT);
            ps.setString(2, "wf-del");
            var rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getTimestamp("deleted_at")).isNotNull();
        }
    }

    @Test
    void delete_returnsFalseWhenNotFound() {
        assertThat(repo.delete(TENANT, "wf-ghost")).isFalse();
    }

    @Test
    void deleteAllForTenant_removesAllAndReturnsCount() {
        repo.save(TENANT, buildWorkflow("wf-1"));
        repo.save(TENANT, buildWorkflow("wf-2"));
        repo.save(TENANT, buildWorkflow("wf-3"));

        int deleted = repo.deleteAllForTenant(TENANT);
        assertThat(deleted).isEqualTo(3);
        assertThat(repo.count(TENANT)).isZero();
    }

    @Test
    void count_reflectsCurrentState() {
        assertThat(repo.count(TENANT)).isZero();

        repo.save(TENANT, buildWorkflow("wf-1"));
        assertThat(repo.count(TENANT)).isEqualTo(1);

        repo.save(TENANT, buildWorkflow("wf-2"));
        assertThat(repo.count(TENANT)).isEqualTo(2);

        repo.delete(TENANT, "wf-1");
        assertThat(repo.count(TENANT)).isEqualTo(1);
    }

    @Test
    void delete_softDeletesButPreservesRowForFK() throws Exception {
        Workflow workflow = buildWorkflow("wf-fk");
        repo.save(TENANT, workflow);

        // Insert an execution_states row referencing this workflow (simulates a past execution)
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                """
                     INSERT INTO runtime.execution_states
                         (tenant_id, execution_id, workflow_id, context, history)
                     VALUES (?, ?, ?, '{}'::jsonb, '{}'::jsonb)
                     """)) {
            ps.setString(1, TENANT);
            ps.setString(2, "exec-1");
            ps.setString(3, "wf-fk");
            ps.executeUpdate();
        }

        // Soft-delete succeeds (hard delete would have thrown FK violation)
        assertThat(repo.delete(TENANT, "wf-fk")).isTrue();

        // Invisible to queries
        assertThat(repo.findById(TENANT, "wf-fk")).isEmpty();
        assertThat(repo.exists(TENANT, "wf-fk")).isFalse();
        assertThat(repo.count(TENANT)).isZero();
        assertThat(repo.findAll(TENANT)).isEmpty();

        // Execution state row is intact
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT 1 FROM runtime.execution_states WHERE tenant_id = ? AND execution_id = ?")) {
            ps.setString(1, TENANT);
            ps.setString(2, "exec-1");
            assertThat(ps.executeQuery().next()).isTrue();
        }
    }

    @Test
    void save_reactivatesSoftDeletedWorkflow() {
        Workflow workflow = buildWorkflow("wf-reactivate");
        repo.save(TENANT, workflow);

        repo.delete(TENANT, "wf-reactivate");
        assertThat(repo.findById(TENANT, "wf-reactivate")).isEmpty();

        // Re-push reactivates
        repo.save(TENANT, workflow);
        assertThat(repo.findById(TENANT, "wf-reactivate")).isPresent();
        assertThat(repo.exists(TENANT, "wf-reactivate")).isTrue();
        assertThat(repo.count(TENANT)).isEqualTo(1);
    }

    @Test
    void delete_returnsFalseForAlreadyDeletedWorkflow() {
        repo.save(TENANT, buildWorkflow("wf-double-del"));

        assertThat(repo.delete(TENANT, "wf-double-del")).isTrue();
        assertThat(repo.delete(TENANT, "wf-double-del")).isFalse();
    }

    @Test
    void tenantIsolation_dataNotVisibleAcrossTenants() {
        repo.save(TENANT, buildWorkflow("shared-id"));
        repo.save(OTHER_TENANT, buildWorkflow("shared-id"));

        assertThat(repo.count(TENANT)).isEqualTo(1);
        assertThat(repo.count(OTHER_TENANT)).isEqualTo(1);

        repo.deleteAllForTenant(TENANT);
        assertThat(repo.count(TENANT)).isZero();
        assertThat(repo.count(OTHER_TENANT)).isEqualTo(1);
    }
}
