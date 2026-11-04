package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.server.execution.Claim;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// The read side of `runtime.execution_states`, as {@link JdbcExecutionStore} serves it to
/// the API's queries. Rows are written the only way the server writes them – under a claim –
/// and read back through the same store.
/// A parent workflow is always saved first to satisfy the foreign key reference.
class JdbcExecutionStoreReadTest extends JdbcRepositoryTestBase {

    private JdbcExecutionStore store;

    @BeforeEach
    void setUp() {
        JdbcWorkflowRepository workflowRepo = new JdbcWorkflowRepository(dataSource);
        store = new JdbcExecutionStore(dataSource, objectMapper, "node-a", Duration.ofSeconds(30));

        deleteExecutions(TENANT);
        deleteExecutions(OTHER_TENANT);
        workflowRepo.deleteAllForTenant(TENANT);
        workflowRepo.deleteAllForTenant(OTHER_TENANT);

        workflowRepo.save(TENANT, buildWorkflow("wf-parent"));
        workflowRepo.save(OTHER_TENANT, buildWorkflow("wf-parent"));
    }

    @Test
    void readsBackWhatTheStoreWrote() {
        store.start(TENANT, makeSnapshot("exec-1", "process"));

        HensuSnapshot s = store.findByExecutionId(TENANT, "exec-1").orElseThrow();
        assertThat(s.executionId()).isEqualTo("exec-1");
        assertThat(s.workflowId()).isEqualTo("wf-parent");
        assertThat(s.currentNodeId()).isEqualTo("process");
        assertThat(s.context()).containsEntry("topic", "AI");
        assertThat(s.checkpointReason()).isEqualTo("checkpoint");
    }

    @Test
    void findPausedListsExactlyTheExecutionsAResumeWouldAccept() {
        Claim paused = store.start(TENANT, makeSnapshot("exec-paused", "process"));
        store.pause(paused, snapshot("exec-paused", "process", awaitingReview("corr"), "paused"));

        store.start(TENANT, makeSnapshot("exec-running", "process"));

        Claim done = store.start(TENANT, makeSnapshot("exec-done", "process"));
        store.finish(done, snapshot("exec-done", null, null, "completed"));

        Claim failed = store.start(TENANT, makeSnapshot("exec-failed", "process"));
        store.finish(failed, snapshot("exec-failed", "process", null, "failed"));

        // Awaiting review but still running: a resume would be refused, so it is not listed.
        store.start(
                TENANT,
                snapshot("exec-awaiting-running", "process", awaitingReview("c2"), "checkpoint"));

        assertThat(store.findPaused(TENANT))
                .extracting(HensuSnapshot::executionId)
                .containsExactly("exec-paused");
    }

    @Test
    void findByWorkflowIdReturnsMatchingSnapshots() {
        store.start(TENANT, makeSnapshot("exec-a", "process"));
        store.start(TENANT, makeSnapshot("exec-b", "done"));

        assertThat(store.findByWorkflowId(TENANT, "wf-parent"))
                .extracting(HensuSnapshot::executionId)
                .containsExactlyInAnyOrder("exec-a", "exec-b");
    }

    @Test
    void tenantsDoNotSeeEachOthersExecutions() {
        store.start(TENANT, makeSnapshot("exec-shared", "process"));
        store.start(OTHER_TENANT, makeSnapshot("exec-shared", "done"));

        assertThat(store.findByExecutionId(TENANT, "exec-shared").orElseThrow().currentNodeId())
                .isEqualTo("process");
        assertThat(
                        store.findByExecutionId(OTHER_TENANT, "exec-shared")
                                .orElseThrow()
                                .currentNodeId())
                .isEqualTo("done");
    }

    @Test
    void historyAndStepRoundTrip() {
        ExecutionHistory history = new ExecutionHistory();
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("process")
                        .result(NodeResult.success("test output", Map.of()))
                        .build());
        Claim claim = store.start(TENANT, makeSnapshot("exec-hist", "process"));

        store.checkpoint(
                claim,
                new HensuSnapshot(
                        "wf-parent",
                        "exec-hist",
                        "done",
                        Map.of("topic", "history"),
                        Map.of(),
                        history,
                        null,
                        Instant.now(),
                        "checkpoint",
                        1));

        HensuSnapshot restored = store.findByExecutionId(TENANT, "exec-hist").orElseThrow();
        assertThat(restored.step()).isEqualTo(1);
        assertThat(restored.history().getSteps()).hasSize(1);
        assertThat(restored.history().getSteps().getFirst().getResult().getStatus())
                .isEqualTo(ResultStatus.SUCCESS);
    }

    // --- Helpers ---

    private static ExecutionPhase awaitingReview(String correlationId) {
        return new ExecutionPhase.Awaiting(
                "process",
                "ReviewPostProcessor",
                new NodeResult(ResultStatus.SUCCESS, "draft", Map.of()),
                correlationId,
                Instant.now());
    }

    private static HensuSnapshot makeSnapshot(String executionId, String currentNodeId) {
        return snapshot(executionId, currentNodeId, null, "checkpoint");
    }

    private static HensuSnapshot snapshot(
            String executionId, String currentNodeId, ExecutionPhase phase, String reason) {
        return new HensuSnapshot(
                "wf-parent",
                executionId,
                currentNodeId,
                Map.of("topic", "AI"),
                Map.of(),
                new ExecutionHistory(),
                phase,
                Instant.now(),
                reason,
                0);
    }
}
