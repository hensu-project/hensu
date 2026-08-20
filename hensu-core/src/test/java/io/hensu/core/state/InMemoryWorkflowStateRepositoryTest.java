package io.hensu.core.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
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

    /// Builds the snapshot a review pause persists: awaiting a decision on the named correlation.
    private HensuSnapshot awaitingSnapshot(
            String executionId,
            String currentNodeId,
            String checkpointReason,
            String correlationId) {
        return new HensuSnapshot(
                "wf-1",
                executionId,
                currentNodeId,
                Map.of(),
                Map.of(),
                null,
                new ExecutionPhase.Awaiting(
                        currentNodeId,
                        "ReviewPostProcessor",
                        new NodeResult(ResultStatus.SUCCESS, "draft", Map.of()),
                        correlationId,
                        Instant.now()),
                Instant.now(),
                checkpointReason);
    }

    private HensuSnapshot createSnapshot(
            String workflowId, String executionId, String currentNodeId, String checkpointReason) {
        return new HensuSnapshot(
                workflowId,
                executionId,
                currentNodeId,
                Map.of(),
                Map.of(),
                null,
                null,
                Instant.now(),
                checkpointReason);
    }

    @Test
    void shouldOverwriteSnapshotOnSameExecution() {
        repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1", "paused"));
        repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-2", "paused"));

        var found = repository.findByExecutionId("tenant-1", "exec-1");
        assertThat(found).isPresent();
        assertThat(found.get().currentNodeId()).isEqualTo("node-2");
        assertThat(repository.countForTenant("tenant-1")).isEqualTo(1);
    }

    @Test
    void shouldIsolateTenantData() {
        repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1", "paused"));
        repository.save("tenant-2", createSnapshot("wf-1", "exec-1", "node-2", "paused"));

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
        repository.save("tenant-1", awaitingSnapshot("exec-1", "node-1", "paused", "corr-1"));
        repository.save("tenant-1", createSnapshot("wf-1", "exec-2", null, "completed"));
        repository.save("tenant-1", awaitingSnapshot("exec-3", "node-2", "paused", "corr-3"));

        List<HensuSnapshot> paused = repository.findPaused("tenant-1");

        assertThat(paused).hasSize(2);
        assertThat(paused)
                .extracting(HensuSnapshot::executionId)
                .containsExactlyInAnyOrder("exec-1", "exec-3");
    }

    /// An execution awaiting review is listed whatever its checkpoint label says.
    ///
    /// Resume looks a snapshot up by id and acts on its phase, so selecting on the
    /// `checkpointReason` label instead hid executions that were genuinely blocked on a human
    /// but had last been saved under the ordinary `checkpoint` reason. This repository must
    /// answer the same question as the JDBC one.
    @Test
    void shouldListAwaitingExecutionSavedAsPlainCheckpoint() {
        repository.save("tenant-1", awaitingSnapshot("exec-1", "review", "checkpoint", "corr-1"));

        assertThat(repository.findPaused("tenant-1"))
                .extracting(HensuSnapshot::executionId)
                .containsExactly("exec-1");
    }

    /// A running execution is not "paused" merely because its node is mid-flight.
    @Test
    void shouldNotListExecutionThatIsMerelyCheckpointed() {
        repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "work", "checkpoint"));

        assertThat(repository.findPaused("tenant-1")).isEmpty();
    }

    @Test
    void shouldDeleteAllForTenantWithoutAffectingOthers() {
        repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1", "paused"));
        repository.save("tenant-1", createSnapshot("wf-1", "exec-2", "node-2", "paused"));
        repository.save("tenant-2", createSnapshot("wf-1", "exec-3", "node-1", "paused"));

        int deleted = repository.deleteAllForTenant("tenant-1");

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.countForTenant("tenant-1")).isZero();
        assertThat(repository.countForTenant("tenant-2")).isEqualTo(1);
    }

    @Test
    void shouldFindByWorkflowIdAcrossExecutions() {
        repository.save("tenant-1", createSnapshot("wf-1", "exec-1", "node-1", "paused"));
        repository.save("tenant-1", createSnapshot("wf-1", "exec-2", "node-2", "paused"));
        repository.save("tenant-1", createSnapshot("wf-2", "exec-3", "node-1", "paused"));

        List<HensuSnapshot> found = repository.findByWorkflowId("tenant-1", "wf-1");

        assertThat(found).hasSize(2);
        assertThat(found)
                .extracting(HensuSnapshot::executionId)
                .containsExactlyInAnyOrder("exec-1", "exec-2");
    }
}
