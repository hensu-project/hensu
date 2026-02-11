package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.service.WorkflowService.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Integration tests for parallel node execution with consensus strategies.
///
/// Covers majority vote, weighted vote, unanimous, and judge-decides
/// consensus strategies across parallel branches with stub agents.
///
/// ### Contracts
/// - **Precondition**: Stub mode enabled (`hensu.stub.enabled=true`)
/// - **Invariant**: All executions use {@link #TEST_TENANT}
///
/// @see IntegrationTestBase for shared test infrastructure
/// @see io.hensu.core.execution.parallel.ConsensusEvaluator for consensus logic
@QuarkusTest
class ParallelNodeIntegrationTest extends IntegrationTestBase {

    @Test
    void shouldReachMajorityConsensus() {
        Workflow workflow = loadWorkflow("parallel-majority-vote.json");
        registerStub("reviewer-1", "I approve this content. It meets quality standards.");
        registerStub("reviewer-2", "I approve this submission.");
        registerStub("reviewer-3", "I reject this content. Needs improvement.");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("consensus-reached");
    }

    @Test
    void shouldFailMajorityWhenInsufficient() {
        Workflow workflow = loadWorkflow("parallel-majority-vote.json");
        registerStub("reviewer-1", "I approve this content.");
        registerStub("reviewer-2", "I reject this submission.");
        registerStub("reviewer-3", "I reject this content.");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("no-consensus");
    }

    @Test
    void shouldReachWeightedConsensus() {
        Workflow workflow = loadWorkflow("parallel-weighted-vote.json");
        registerStub("senior-reviewer", "I approve this content. Excellent quality.");
        registerStub("junior-reviewer", "I reject this content. Not good enough.");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("consensus-reached");
    }

    @Test
    void shouldRequireUnanimousApproval() {
        Workflow workflow = loadWorkflow("parallel-unanimous.json");
        registerStub("reviewer-1", "I approve this content.");
        registerStub("reviewer-2", "I approve this content.");
        registerStub("reviewer-3", "I approve this content.");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("consensus-reached");
    }

    @Test
    void shouldFailUnanimousWhenOneRejects() {
        Workflow workflow = loadWorkflow("parallel-unanimous.json");
        registerStub("reviewer-1", "I approve this content.");
        registerStub("reviewer-2", "I approve this content.");
        registerStub("reviewer-3", "I reject this content.");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("no-consensus");
    }

    @Test
    void shouldUseJudgeForDecision() {
        Workflow workflow = loadWorkflow("parallel-judge-decides.json");
        registerStub("reviewer-1", "I approve the content.");
        registerStub("reviewer-2", "I reject the content.");
        registerStub("judge", "After reviewing both opinions, I approve this content.");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("consensus-reached");
    }
}
