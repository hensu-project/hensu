package io.hensu.core.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryWorkflowStateRepositoryTest {

    private InMemoryWorkflowStateRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryWorkflowStateRepository();
    }

    private HensuSnapshot createSnapshot(
            String workflowId, String executionId, String currentNodeId) {
        return new HensuSnapshot(
                workflowId, executionId, currentNodeId, Map.of(), null, null, Instant.now(), null);
    }

    @Test
    void shouldOverwriteSnapshotOnSameExecution() {
        repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1"));
        repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-2"));

        var found = repository.findByExecutionId("tenant-1", "exec-1");
        assertThat(found).isPresent();
        assertThat(found.get().currentNodeId()).isEqualTo("node-2");
        assertThat(repository.countForTenant("tenant-1")).isEqualTo(1);
    }

    @Test
    void shouldIsolateTenantData() {
        repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1"));
        repository.save("tenant-2", createSnapshot("wf-1", "exec-1", "node-2"));

        // Same executionId, different tenants – must not cross-contaminate
        assertThat(repository.findByExecutionId("tenant-1", "exec-1").orElseThrow().currentNodeId())
                .isEqualTo("node-1");
        assertThat(repository.findByExecutionId("tenant-2", "exec-1").orElseThrow().currentNodeId())
                .isEqualTo("node-2");

        // Delete from tenant-2 must not affect tenant-1
        repository.delete("tenant-2", "exec-1");
        assertThat(repository.findByExecutionId("tenant-1", "exec-1")).isPresent();
        assertThat(repository.findByExecutionId("tenant-2", "exec-1")).isEmpty();
    }

    @Test
    void shouldFilterPausedFromCompleted() {
        repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1"));
        repository.save("tenant-1", createSnapshot("wf-1", "exec-2", null)); // completed
        repository.save("tenant-1", createSnapshot("wf-1", "exec-3", "node-2"));

        List<HensuSnapshot> paused = repository.findPaused("tenant-1");

        assertThat(paused).hasSize(2);
        assertThat(paused)
                .extracting(HensuSnapshot::executionId)
                .containsExactlyInAnyOrder("exec-1", "exec-3");
    }

    @Test
    void shouldDeleteAllForTenantWithoutAffectingOthers() {
        repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1"));
        repository.save("tenant-1", createSnapshot("wf-1", "exec-2", "node-2"));
        repository.save("tenant-2", createSnapshot("wf-1", "exec-3", "node-1"));

        int deleted = repository.deleteAllForTenant("tenant-1");

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.countForTenant("tenant-1")).isZero();
        assertThat(repository.countForTenant("tenant-2")).isEqualTo(1);
    }

    @Test
    void shouldFindByWorkflowIdAcrossExecutions() {
        repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1"));
        repository.save("tenant-1", createSnapshot("wf-1", "exec-2", "node-2"));
        repository.save("tenant-1", createSnapshot("wf-2", "exec-3", "node-1"));

        List<HensuSnapshot> found = repository.findByWorkflowId("tenant-1", "wf-1");

        assertThat(found).hasSize(2);
        assertThat(found)
                .extracting(HensuSnapshot::executionId)
                .containsExactlyInAnyOrder("exec-1", "exec-2");
    }
}
