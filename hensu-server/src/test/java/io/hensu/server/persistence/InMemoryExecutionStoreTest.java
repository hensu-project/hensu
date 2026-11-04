package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.InMemoryWorkflowStateRepository;
import io.hensu.server.execution.Claim;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// The `inmem` profile serves the same API as PostgreSQL, so its store must answer the
/// queries with the same filters as {@link JdbcExecutionStoreReadTest} pins for the database.
class InMemoryExecutionStoreTest {

    private static final String TENANT = "tenant-1";

    private final InMemoryExecutionStore store =
            new InMemoryExecutionStore(new InMemoryWorkflowStateRepository(), (_, _) -> {});

    @Test
    void findPausedListsExactlyTheExecutionsAResumeWouldAccept() {
        Claim paused = store.start(TENANT, snapshot("exec-paused", null, "checkpoint"));
        store.pause(paused, snapshot("exec-paused", awaitingReview("corr"), "paused"));

        Claim done = store.start(TENANT, snapshot("exec-done", null, "checkpoint"));
        store.finish(done, snapshot("exec-done", null, "completed"));

        // Awaiting review but still running: a resume would be refused, so it is not listed.
        store.start(TENANT, snapshot("exec-awaiting-running", awaitingReview("c2"), "checkpoint"));

        assertThat(store.findPaused(TENANT))
                .extracting(HensuSnapshot::executionId)
                .containsExactly("exec-paused");
        assertThat(store.resume(TENANT, "exec-awaiting-running")).isEmpty();
    }

    private static ExecutionPhase awaitingReview(String correlationId) {
        return new ExecutionPhase.Awaiting(
                "process",
                "ReviewPostProcessor",
                new NodeResult(ResultStatus.SUCCESS, "draft", Map.of()),
                correlationId,
                Instant.now());
    }

    private static HensuSnapshot snapshot(String executionId, ExecutionPhase phase, String reason) {
        return new HensuSnapshot(
                "wf",
                executionId,
                "process",
                Map.of(),
                Map.of(),
                new ExecutionHistory(),
                phase,
                Instant.now(),
                reason,
                0);
    }
}
