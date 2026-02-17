package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.service.WorkflowService.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Integration tests for standard (single-agent) node execution.
///
/// Covers basic execution, output parameter extraction, score-based
/// transition routing, and multistep chaining through standard nodes.
///
/// ### Contracts
/// - **Precondition**: Stub mode enabled (`hensu.stub.enabled=true`)
/// - **Invariant**: All executions use {@link #TEST_TENANT}
///
/// @see IntegrationTestBase for shared test infrastructure
/// @see io.hensu.core.workflow.node.StandardNode for the node type under test
@QuarkusTest
class StandardNodeIntegrationTest extends IntegrationTestBase {

    @Test
    void shouldExecuteBasicStandardNode() {
        Workflow workflow = loadWorkflow("standard-basic.json");
        registerStub("process", "This is a well-written summary about AI.");

        ExecutionStartResult result =
                pushAndExecute(workflow, Map.of("topic", "artificial intelligence"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.context())
                .containsEntry("process", "This is a well-written summary about AI.");
    }

    @Test
    void shouldExtractOutputParams() {
        Workflow workflow = loadWorkflow("standard-output-params.json");
        registerStub(
                "analyze",
                "{\"sentiment\":\"positive\",\"confidence\":\"0.95\",\"keywords\":[\"ai\",\"ml\"]}");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("text", "AI is great"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.context())
                .containsEntry("sentiment", "positive")
                .containsEntry("confidence", "0.95");
    }

    @Test
    void shouldRouteToHighQualityOnHighScore() {
        Workflow workflow = loadWorkflow("standard-score-transitions.json");
        registerStub("evaluate", "{\"score\": 9.5}");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.currentNodeId()).isEqualTo("high-quality");
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
    }

    @Test
    void shouldRouteToMediumQualityOnMediumScore() {
        Workflow workflow = loadWorkflow("standard-score-transitions.json");
        registerStub("evaluate", "{\"score\": 6.0}");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.currentNodeId()).isEqualTo("medium-quality");
    }

    @Test
    void shouldRouteToLowQualityOnLowScore() {
        Workflow workflow = loadWorkflow("standard-score-transitions.json");
        registerStub("evaluate", "{\"score\": 2.0}");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("content", "test content"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.currentNodeId()).isEqualTo("low-quality");
    }

    @Test
    void shouldExecuteMultiStepChain() {
        Workflow workflow = loadWorkflow("multi-step-chain.json");
        registerStub("step-a", "Research findings about topic X");
        registerStub("step-b", "Article draft based on research");
        registerStub("step-c", "Polished final article");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("topic", "technology"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.context())
                .containsEntry("step-a", "Research findings about topic X")
                .containsEntry("step-b", "Article draft based on research")
                .containsEntry("step-c", "Polished final article");
    }
}
