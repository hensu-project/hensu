package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /// A resume that does not answer the pending review must fail loudly and change nothing.
    ///
    /// Before this guard the executor re-entered the post-pipeline, hit the review processor
    /// again, and requested a fresh review: HTTP 200, a new correlation id, and no decision
    /// applied — a loop the caller could not distinguish from success. The failure must also
    /// stay a *request* failure: the execution is merely paused, so marking it failed would be
    /// worse than the loop it replaces.
    @Test
    void shouldRejectNonReviewResumeWhileAwaitingAndStayResumable() {
        Workflow workflow = loadWorkflow("review-approve.json");
        registerStub("draft", "Draft content about testing");
        testReviewHandler.enqueueOutcome(ReviewOutcome.pending("corr-3"));

        workflowRepository.save(TEST_TENANT, workflow);
        ExecutionStartResult result =
                workflowService.startExecution(
                        TEST_TENANT, workflow.getId(), Map.of("topic", "testing"));

        awaitCheckpointReason(result.executionId(), "paused");

        assertThatThrownBy(
                        () ->
                                workflowService.resumeExecution(
                                        TEST_TENANT, result.executionId(), ResumeInput.NONE))
                .isInstanceOf(IllegalArgumentException.class);

        HensuSnapshot afterBadResume =
                workflowStateRepository
                        .findByExecutionId(TEST_TENANT, result.executionId())
                        .orElseThrow();
        assertThat(afterBadResume.checkpointReason()).isEqualTo("paused");
        assertThat(afterBadResume.phase()).isInstanceOf(ExecutionPhase.Awaiting.class);
        assertThat(((ExecutionPhase.Awaiting) afterBadResume.phase()).correlationId())
                .isEqualTo("corr-3");

        // The rejected request left the execution usable: a correct one still completes it.
        workflowService.resumeExecution(
                TEST_TENANT,
                result.executionId(),
                new ResumeInput.ApplyReview("corr-3", new ReviewDecision.Approve()));

        awaitCheckpointReason(result.executionId(), "completed");
    }

    /// A decision that arrives after the review was already answered must not reopen the run.
    ///
    /// Webhooks and review UIs retry: the same approval can be submitted twice, and the second
    /// call lands on an execution whose phase is no longer `Awaiting`. Applying it would drive
    /// `executeFrom` over a terminal state, so the request must be rejected while the finished
    /// snapshot — checkpoint reason, phase, and context — stays exactly as the first resume
    /// left it.
    @Test
    void shouldRejectStaleReviewDecisionAfterExecutionCompleted() {
        Workflow workflow = loadWorkflow("review-approve.json");
        registerStub("draft", "Draft content about testing");
        testReviewHandler.enqueueOutcome(ReviewOutcome.pending("corr-4"));

        workflowRepository.save(TEST_TENANT, workflow);
        ExecutionStartResult result =
                workflowService.startExecution(
                        TEST_TENANT, workflow.getId(), Map.of("topic", "testing"));

        awaitCheckpointReason(result.executionId(), "paused");

        var decision = new ResumeInput.ApplyReview("corr-4", new ReviewDecision.Approve());
        workflowService.resumeExecution(TEST_TENANT, result.executionId(), decision);

        awaitCheckpointReason(result.executionId(), "completed");
        HensuSnapshot completed =
                workflowStateRepository
                        .findByExecutionId(TEST_TENANT, result.executionId())
                        .orElseThrow();

        assertThatThrownBy(
                        () ->
                                workflowService.resumeExecution(
                                        TEST_TENANT, result.executionId(), decision))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not awaiting review");

        HensuSnapshot afterStaleDecision =
                workflowStateRepository
                        .findByExecutionId(TEST_TENANT, result.executionId())
                        .orElseThrow();
        assertThat(afterStaleDecision.checkpointReason()).isEqualTo("completed");
        assertThat(afterStaleDecision.phase()).isInstanceOf(ExecutionPhase.Terminal.class);
        assertThat(afterStaleDecision.context()).isEqualTo(completed.context());
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
