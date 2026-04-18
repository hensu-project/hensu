package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.workflow.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Integration tests for `ApprovalTransition` end-to-end routing.
///
/// Covers two scenarios using stub agents:
/// - Simple approval routing: node writes `approved`, engine routes on the boolean
/// - Score + approval routing: regression for the original bug where `ScoreTransition`
///   fired on a high score even when `approved=false`, routing to the wrong node
///
/// ### Contracts
/// - **Precondition**: Stub mode enabled (`hensu.stub.enabled=true`)
/// - **Invariant**: All executions use {@link #TEST_TENANT}
///
/// @see IntegrationTestBase for shared test infrastructure
/// @see io.hensu.core.workflow.transition.ApprovalTransition
@QuarkusTest
class ApprovalRoutingIntegrationTest extends IntegrationTestBase {

    @Test
    void shouldRouteToFinalizeWhenAgentApproves() {
        Workflow workflow = loadWorkflow("approval-routing.json");
        registerStub("review", "{\"approved\": true}");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        HensuSnapshot snapshot = lastSnapshot(result);
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("finalize");
    }

    @Test
    void shouldRouteToImproveWhenAgentRejects() {
        Workflow workflow = loadWorkflow("approval-routing.json");
        registerStub("review", "{\"approved\": false}");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        HensuSnapshot snapshot = lastSnapshot(result);
        assertThat(snapshot.currentNodeId()).isEqualTo("improve");
    }

    @Test
    void shouldRouteByApprovalNotScoreWhenScoreHighButAgentRejects() {
        // Regression: engine previously evaluated ScoreTransition GTE threshold first,
        // routing to "finalize" even when approved=false. Now approval takes precedence
        // over a passing score — the engine must route to "improve", not "finalize".
        Workflow workflow = loadWorkflow("score-and-approval-routing.json");
        registerStub("review", "{\"score\": 88, \"approved\": false}");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        HensuSnapshot snapshot = lastSnapshot(result);
        assertThat(snapshot.currentNodeId())
                .as("high score must not override explicit agent rejection")
                .isEqualTo("improve");
    }

    @Test
    void shouldRouteToFinalizeWhenScoreHighAndAgentApproves() {
        Workflow workflow = loadWorkflow("score-and-approval-routing.json");
        registerStub("review", "{\"score\": 88, \"approved\": true}");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        HensuSnapshot snapshot = lastSnapshot(result);
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("finalize");
    }

    @Test
    void shouldRouteToCriticalFailWhenScoreBelowThresholdRegardlessOfApproval() {
        // Score LT 60 is the first rule — it fires before approval rules regardless of approved
        // value
        Workflow workflow = loadWorkflow("score-and-approval-routing.json");
        registerStub("review", "{\"score\": 40, \"approved\": true}");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        HensuSnapshot snapshot = lastSnapshot(result);
        assertThat(snapshot.currentNodeId()).isEqualTo("critical-fail");
    }

    private HensuSnapshot lastSnapshot(ExecutionStartResult result) {
        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();
        return snapshots.getLast();
    }
}
