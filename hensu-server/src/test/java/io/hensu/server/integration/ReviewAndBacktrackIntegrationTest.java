package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.result.BacktrackEvent;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.workflow.WorkflowService.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Integration tests for review checkpoint and backtrack behavior.
///
/// Verifies that the review handler integration works end-to-end:
/// approve, backtrack to a previous step, reject, and default decision
/// fallback. Uses {@link TestReviewHandler} to script review decisions.
///
/// ### Contracts
/// - **Precondition**: Stub mode enabled (`hensu.stub.enabled=true`)
/// - **Invariant**: All executions use {@link #TEST_TENANT}
///
/// @see TestReviewHandler for the configurable review handler
/// @see IntegrationTestBase for shared test infrastructure
/// @see io.hensu.core.review.ReviewDecision for the decision sealed hierarchy
@QuarkusTest
class ReviewAndBacktrackIntegrationTest extends IntegrationTestBase {

    @Inject TestReviewHandler testReviewHandler;

    @BeforeEach
    void resetReviewHandler() {
        testReviewHandler.reset();
    }

    @Test
    void shouldApproveAndContinue() {
        Workflow workflow = loadWorkflow("review-approve.json");
        registerStub("draft", "Written content about the topic");
        testReviewHandler.enqueueDecision(new ReviewDecision.Approve());

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("topic", "test"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("done");
    }

    @Test
    void shouldBacktrackToTargetStep() {
        Workflow workflow = loadWorkflow("review-backtrack.json");
        registerStub("research", "Research findings");
        registerStub("draft", "Draft article");

        testReviewHandler.enqueueDecision(
                new ReviewDecision.Backtrack("research", null, "Needs more research"));
        testReviewHandler.enqueueDecision(new ReviewDecision.Approve());

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("topic", "test"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("done");

        List<BacktrackEvent> backtracks = snapshot.history().getBacktracks();
        assertThat(backtracks).isNotEmpty();
        assertThat(backtracks.getFirst().getFrom()).isEqualTo("draft");
        assertThat(backtracks.getFirst().getTo()).isEqualTo("research");
    }

    @Test
    void shouldRejectWorkflow() {
        Workflow workflow = loadWorkflow("review-reject.json");
        registerStub("draft", "Content");
        testReviewHandler.enqueueDecision(new ReviewDecision.Reject("Quality insufficient"));

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("topic", "test"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("rejected");
    }

    @Test
    void shouldApproveWithDefaultDecision() {
        Workflow workflow = loadWorkflow("review-approve.json");
        registerStub("draft", "Good content");
        testReviewHandler.setDefaultDecision(new ReviewDecision.Approve());

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("topic", "test"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("done");
    }
}
