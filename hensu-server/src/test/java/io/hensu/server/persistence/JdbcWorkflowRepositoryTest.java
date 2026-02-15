package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.workflow.Workflow;
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
    void delete_returnsTrueWhenDeleted() {
        repo.save(TENANT, buildWorkflow("wf-del"));

        assertThat(repo.delete(TENANT, "wf-del")).isTrue();
        assertThat(repo.findById(TENANT, "wf-del")).isEmpty();
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
