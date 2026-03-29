package io.hensu.server.integration;

import static io.hensu.core.execution.EngineVariables.APPROVED;
import static io.hensu.core.execution.EngineVariables.RECOMMENDATION;
import static io.hensu.core.execution.EngineVariables.SCORE;
import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.workflow.WorkflowService.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Integration tests for parallel node execution with consensus strategies.
///
/// Stubs return structured JSON with engine variables – the same contract
/// real LLM agents produce when the prompt enrichment pipeline injects
/// output requirements.
///
/// @see IntegrationTestBase for shared test infrastructure
/// @see io.hensu.core.execution.parallel.ConsensusEvaluator for consensus logic
@QuarkusTest
class ParallelNodeIntegrationTest extends IntegrationTestBase {

    @Test
    void shouldReachMajorityConsensus() {
        Workflow workflow = loadWorkflow("parallel-majority-vote.json");
        registerStub("reviewer-1", vote(90, true, "Meets quality standards"));
        registerStub("reviewer-2", vote(85, true, "Good submission"));
        registerStub("reviewer-3", vote(30, false, "Needs improvement"));

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        HensuSnapshot snapshot = terminalSnapshot(result);
        assertThat(snapshot.currentNodeId()).isEqualTo("consensus-reached");
    }

    @Test
    void shouldFailMajorityWhenInsufficient() {
        Workflow workflow = loadWorkflow("parallel-majority-vote.json");
        registerStub("reviewer-1", vote(80, true, "Looks good"));
        registerStub("reviewer-2", vote(25, false, "Poor quality"));
        registerStub("reviewer-3", vote(20, false, "Reject"));

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        HensuSnapshot snapshot = terminalSnapshot(result);
        assertThat(snapshot.currentNodeId()).isEqualTo("no-consensus");
    }

    @Test
    void shouldReachWeightedConsensus() {
        Workflow workflow = loadWorkflow("parallel-weighted-vote.json");
        // Senior (weight=2.0) approves, junior (weight=1.0) rejects
        // Weighted approve = 90*2.0=180, reject = 30*1.0=30 → ratio=0.86 > 0.5
        registerStub("senior-reviewer", vote(90, true, "Excellent quality"));
        registerStub("junior-reviewer", vote(30, false, "Not good enough"));

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        HensuSnapshot snapshot = terminalSnapshot(result);
        assertThat(snapshot.currentNodeId()).isEqualTo("consensus-reached");
    }

    @Test
    void shouldRequireUnanimousApproval() {
        Workflow workflow = loadWorkflow("parallel-unanimous.json");
        registerStub("reviewer-1", vote(95, true, "Great"));
        registerStub("reviewer-2", vote(80, true, "Good"));
        registerStub("reviewer-3", vote(72, true, "Acceptable"));

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        HensuSnapshot snapshot = terminalSnapshot(result);
        assertThat(snapshot.currentNodeId()).isEqualTo("consensus-reached");
    }

    @Test
    void shouldFailUnanimousWhenOneRejects() {
        Workflow workflow = loadWorkflow("parallel-unanimous.json");
        registerStub("reviewer-1", vote(90, true, "Good"));
        registerStub("reviewer-2", vote(85, true, "Fine"));
        registerStub("reviewer-3", vote(40, false, "Needs work"));

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        HensuSnapshot snapshot = terminalSnapshot(result);
        assertThat(snapshot.currentNodeId()).isEqualTo("no-consensus");
    }

    @Test
    void shouldUseJudgeForDecision() {
        Workflow workflow = loadWorkflow("parallel-judge-decides.json");
        registerStub("reviewer-1", vote(85, true, "Approve"));
        registerStub("reviewer-2", vote(25, false, "Reject"));
        registerStub(
                "judge",
                """
                {"decision": true, "winning_branch": "branch-1", \
                "reasoning": "Branch 1 quality is superior"}""");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        HensuSnapshot snapshot = terminalSnapshot(result);
        assertThat(snapshot.currentNodeId()).isEqualTo("consensus-reached");
    }

    // -- Helpers --------------------------------------------------------------

    /// Builds a structured JSON vote response using engine variable constants.
    private static String vote(double score, boolean approved, String recommendation) {
        return String.format(
                "{\"%s\": %s, \"%s\": %s, \"%s\": \"%s\"}",
                SCORE, score, APPROVED, approved, RECOMMENDATION, recommendation);
    }

    private HensuSnapshot terminalSnapshot(ExecutionStartResult result) {
        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();
        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        return snapshot;
    }
}
