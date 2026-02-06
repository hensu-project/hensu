package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.state.HensuSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

    @Nested
    class Save {

        @Test
        void shouldSaveSnapshot() {
            HensuSnapshot snapshot = createSnapshot("wf-1", "exec-1", "node-1");

            repository.save("tenant-1", snapshot);

            assertThat(repository.countForTenant("tenant-1")).isEqualTo(1);
        }

        @Test
        void shouldUpdateExistingSnapshot() {
            HensuSnapshot snapshot1 = createSnapshot("wf-1", "exec-1", "node-1");
            HensuSnapshot snapshot2 = createSnapshot("wf-1", "exec-1", "node-2");

            repository.save("tenant-1", snapshot1);
            repository.save("tenant-1", snapshot2);

            Optional<HensuSnapshot> found = repository.findByExecutionId("tenant-1", "exec-1");
            assertThat(found).isPresent();
            assertThat(found.get().currentNodeId()).isEqualTo("node-2");
        }

        @Test
        void shouldIsolateTenants() {
            HensuSnapshot snapshot1 = createSnapshot("wf-1", "exec-1", "node-1");
            HensuSnapshot snapshot2 = createSnapshot("wf-1", "exec-1", "node-2");

            repository.save("tenant-1", snapshot1);
            repository.save("tenant-2", snapshot2);

            assertThat(repository.findByExecutionId("tenant-1", "exec-1").get().currentNodeId())
                    .isEqualTo("node-1");
            assertThat(repository.findByExecutionId("tenant-2", "exec-1").get().currentNodeId())
                    .isEqualTo("node-2");
        }

        @Test
        void shouldRejectNullTenantId() {
            HensuSnapshot snapshot = createSnapshot("wf-1", "exec-1", "node-1");

            assertThatThrownBy(() -> repository.save(null, snapshot))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("tenantId");
        }

        @Test
        void shouldRejectNullSnapshot() {
            assertThatThrownBy(() -> repository.save("tenant-1", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("snapshot");
        }
    }

    @Nested
    class FindByExecutionId {

        @Test
        void shouldFindExistingSnapshot() {
            HensuSnapshot snapshot = createSnapshot("wf-1", "exec-1", "node-1");
            repository.save("tenant-1", snapshot);

            Optional<HensuSnapshot> found = repository.findByExecutionId("tenant-1", "exec-1");

            assertThat(found).isPresent();
            assertThat(found.get().executionId()).isEqualTo("exec-1");
        }

        @Test
        void shouldReturnEmptyForNonExistent() {
            Optional<HensuSnapshot> found = repository.findByExecutionId("tenant-1", "exec-1");

            assertThat(found).isEmpty();
        }

        @Test
        void shouldReturnEmptyForWrongTenant() {
            HensuSnapshot snapshot = createSnapshot("wf-1", "exec-1", "node-1");
            repository.save("tenant-1", snapshot);

            Optional<HensuSnapshot> found = repository.findByExecutionId("tenant-2", "exec-1");

            assertThat(found).isEmpty();
        }

        @Test
        void shouldRejectNullTenantId() {
            assertThatThrownBy(() -> repository.findByExecutionId(null, "exec-1"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("tenantId");
        }

        @Test
        void shouldRejectNullExecutionId() {
            assertThatThrownBy(() -> repository.findByExecutionId("tenant-1", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("executionId");
        }
    }

    @Nested
    class FindPaused {

        @Test
        void shouldFindPausedSnapshots() {
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
        void shouldReturnEmptyForNoSnapshots() {
            List<HensuSnapshot> paused = repository.findPaused("tenant-1");

            assertThat(paused).isEmpty();
        }

        @Test
        void shouldReturnEmptyWhenAllCompleted() {
            repository.save("tenant-1", createSnapshot("wf-1", "exec-1", null));
            repository.save("tenant-1", createSnapshot("wf-1", "exec-2", null));

            List<HensuSnapshot> paused = repository.findPaused("tenant-1");

            assertThat(paused).isEmpty();
        }

        @Test
        void shouldRejectNullTenantId() {
            assertThatThrownBy(() -> repository.findPaused(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("tenantId");
        }
    }

    @Nested
    class FindByWorkflowId {

        @Test
        void shouldFindByWorkflowId() {
            repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1"));
            repository.save("tenant-1", createSnapshot("wf-1", "exec-2", "node-2"));
            repository.save("tenant-1", createSnapshot("wf-2", "exec-3", "node-1"));

            List<HensuSnapshot> found = repository.findByWorkflowId("tenant-1", "wf-1");

            assertThat(found).hasSize(2);
            assertThat(found)
                    .extracting(HensuSnapshot::executionId)
                    .containsExactlyInAnyOrder("exec-1", "exec-2");
        }

        @Test
        void shouldReturnEmptyForNonExistentWorkflow() {
            List<HensuSnapshot> found = repository.findByWorkflowId("tenant-1", "wf-1");

            assertThat(found).isEmpty();
        }

        @Test
        void shouldRejectNullTenantId() {
            assertThatThrownBy(() -> repository.findByWorkflowId(null, "wf-1"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("tenantId");
        }

        @Test
        void shouldRejectNullWorkflowId() {
            assertThatThrownBy(() -> repository.findByWorkflowId("tenant-1", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("workflowId");
        }
    }

    @Nested
    class Delete {

        @Test
        void shouldDeleteSnapshot() {
            repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1"));

            boolean deleted = repository.delete("tenant-1", "exec-1");

            assertThat(deleted).isTrue();
            assertThat(repository.findByExecutionId("tenant-1", "exec-1")).isEmpty();
        }

        @Test
        void shouldReturnFalseForNonExistent() {
            boolean deleted = repository.delete("tenant-1", "exec-1");

            assertThat(deleted).isFalse();
        }

        @Test
        void shouldNotDeleteFromOtherTenant() {
            repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1"));

            boolean deleted = repository.delete("tenant-2", "exec-1");

            assertThat(deleted).isFalse();
            assertThat(repository.findByExecutionId("tenant-1", "exec-1")).isPresent();
        }

        @Test
        void shouldRejectNullTenantId() {
            assertThatThrownBy(() -> repository.delete(null, "exec-1"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("tenantId");
        }

        @Test
        void shouldRejectNullExecutionId() {
            assertThatThrownBy(() -> repository.delete("tenant-1", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("executionId");
        }
    }

    @Nested
    class DeleteAllForTenant {

        @Test
        void shouldDeleteAllForTenant() {
            repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1"));
            repository.save("tenant-1", createSnapshot("wf-1", "exec-2", "node-2"));
            repository.save("tenant-2", createSnapshot("wf-1", "exec-3", "node-1"));

            int deleted = repository.deleteAllForTenant("tenant-1");

            assertThat(deleted).isEqualTo(2);
            assertThat(repository.countForTenant("tenant-1")).isZero();
            assertThat(repository.countForTenant("tenant-2")).isEqualTo(1);
        }

        @Test
        void shouldReturnZeroForNonExistentTenant() {
            int deleted = repository.deleteAllForTenant("tenant-1");

            assertThat(deleted).isZero();
        }

        @Test
        void shouldRejectNullTenantId() {
            assertThatThrownBy(() -> repository.deleteAllForTenant(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("tenantId");
        }
    }

    @Nested
    class Clear {

        @Test
        void shouldClearAllData() {
            repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1"));
            repository.save("tenant-2", createSnapshot("wf-1", "exec-2", "node-1"));

            repository.clear();

            assertThat(repository.countForTenant("tenant-1")).isZero();
            assertThat(repository.countForTenant("tenant-2")).isZero();
        }
    }
}
