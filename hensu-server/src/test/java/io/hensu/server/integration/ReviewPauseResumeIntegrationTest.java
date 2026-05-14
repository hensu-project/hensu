package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.resume.ResumeInput;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewOutcome;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.workflow.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Gate test for Phase 4: interactive review pause/resume cycle.
///
/// Verifies the full flow: execute → review handler returns Pending →
/// execution pauses with Awaiting phase → resume with
/// ApplyReview(Approve) → execution completes.
@QuarkusTest
class ReviewPauseResumeIntegrationTest extends IntegrationTestBase {

    @Inject TestReviewHandler testReviewHandler;

    @BeforeEach
    void resetHandler() {
        testReviewHandler.reset();
    }

    @Test
    void shouldPauseOnPendingReviewThenResumeWithApproval() {
        Workflow workflow = loadWorkflow("review-approve.json");
        registerStub("draft", "Draft content about testing");

        testReviewHandler.enqueueOutcome(ReviewOutcome.pending("corr-1"));

        workflowRepository.save(TEST_TENANT, workflow);
        ExecutionStartResult result =
                workflowService.startExecution(
                        TEST_TENANT, workflow.getId(), Map.of("topic", "testing"));

        awaitCheckpointReason(result.executionId(), "paused");

        HensuSnapshot paused =
                workflowStateRepository
                        .findByExecutionId(TEST_TENANT, result.executionId())
                        .orElseThrow();
        assertThat(paused.checkpointReason()).isEqualTo("paused");
        assertThat(paused.phase()).isInstanceOf(ExecutionPhase.Awaiting.class);

        ExecutionPhase.Awaiting awaitingPhase = (ExecutionPhase.Awaiting) paused.phase();
        assertThat(awaitingPhase.processorId()).isEqualTo("ReviewPostProcessor");
        assertThat(awaitingPhase.correlationId()).isEqualTo("corr-1");

        workflowService.resumeExecution(
                TEST_TENANT,
                result.executionId(),
                new ResumeInput.ApplyReview("corr-1", new ReviewDecision.Approve()));

        awaitCheckpointReason(result.executionId(), "completed");

        HensuSnapshot completed =
                workflowStateRepository
                        .findByExecutionId(TEST_TENANT, result.executionId())
                        .orElseThrow();
        assertThat(completed.checkpointReason()).isEqualTo("completed");
        assertThat(completed.context()).containsKey("draft");
    }

    @Test
    void shouldPauseOnPendingReviewThenRejectTerminates() {
        Workflow workflow = loadWorkflow("review-approve.json");
        registerStub("draft", "Draft content about testing");

        testReviewHandler.enqueueOutcome(ReviewOutcome.pending("corr-2"));

        workflowRepository.save(TEST_TENANT, workflow);
        ExecutionStartResult result =
                workflowService.startExecution(
                        TEST_TENANT, workflow.getId(), Map.of("topic", "testing"));

        awaitCheckpointReason(result.executionId(), "paused");

        workflowService.resumeExecution(
                TEST_TENANT,
                result.executionId(),
                new ResumeInput.ApplyReview("corr-2", new ReviewDecision.Reject("Not acceptable")));

        awaitCheckpointReason(result.executionId(), "rejected");

        HensuSnapshot rejected =
                workflowStateRepository
                        .findByExecutionId(TEST_TENANT, result.executionId())
                        .orElseThrow();
        assertThat(rejected.checkpointReason()).isEqualTo("rejected");
    }

    private void awaitCheckpointReason(String executionId, String expectedReason) {
        io.smallrye.mutiny.Uni.createFrom()
                .deferred(
                        () -> {
                            var snapshot =
                                    workflowStateRepository.findByExecutionId(
                                            TEST_TENANT, executionId);
                            assertThat(snapshot).isPresent();
                            assertThat(snapshot.get().checkpointReason()).isEqualTo(expectedReason);
                            return io.smallrye.mutiny.Uni.createFrom().item(snapshot.get());
                        })
                .onFailure(AssertionError.class)
                .retry()
                .withBackOff(java.time.Duration.ofMillis(10), java.time.Duration.ofMillis(10))
                .indefinitely()
                .await()
                .atMost(java.time.Duration.ofSeconds(5));
    }
}
