package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.workflow.WorkflowService.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Integration tests for specialized node types: action, end-status, generic, and fork-join.
///
/// Covers action node dispatch, end node termination statuses, generic node handler
/// delegation, and fork-join parallel execution with result merging.
///
/// ### Contracts
/// - **Precondition**: Stub mode enabled (`hensu.stub.enabled=true`)
/// - **Invariant**: All executions use {@link #TEST_TENANT}
///
/// @see IntegrationTestBase for shared test infrastructure
/// @see TestActionHandler for the `"test-tool"` action handler
/// @see TestValidatorHandler for the `"validator"` generic node handler
@QuarkusTest
class SpecializedNodeIntegrationTest extends IntegrationTestBase {

    @Inject TestActionHandler testActionHandler;

    @Inject ActionExecutor actionExecutor;

    /// Resets the test action handler and ensures it is registered before each test.
    @BeforeEach
    void resetActionHandler() {
        testActionHandler.reset();
        actionExecutor.registerHandler(testActionHandler);
    }

    @Test
    void shouldExecuteActionNode() {
        Workflow workflow = loadWorkflow("action-node.json");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of());

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");

        List<Map<String, Object>> payloads = testActionHandler.getReceivedPayloads();
        assertThat(payloads).hasSize(2);
        assertThat(payloads.getFirst()).containsEntry("channel", "#general");
        assertThat(payloads.get(1)).containsEntry("action", "log");
    }

    @Test
    void shouldTerminateWithSuccessStatus() {
        Workflow workflow = loadWorkflow("end-node-statuses.json");
        registerStub("evaluate", "{\"score\": 9.0}");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("input", "test"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("success-end");
    }

    @Test
    void shouldTerminateWithFailureStatus() {
        Workflow workflow = loadWorkflow("end-node-statuses.json");
        registerStub("evaluate", "{\"score\": 2.0}");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("input", "test"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("failure-end");
    }

    @Test
    void shouldTerminateWithCancelledStatus() {
        Workflow workflow = loadWorkflow("end-node-statuses.json");
        registerStub("evaluate", "{\"score\": 5.0}");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("input", "test"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("cancel-end");
    }

    @Test
    void shouldExecuteGenericNodeWithHandler() {
        Workflow workflow = loadWorkflow("generic-node.json");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of());

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.context()).containsKey("validate");
        assertThat(snapshot.context().get("validate").toString()).contains("Validation passed");
    }

    @Test
    void shouldExecuteForkJoin() {
        Workflow workflow = loadWorkflow("fork-join.json");
        registerStub("process-a", "Result from processor A");
        registerStub("process-b", "Result from processor B");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("input", "test data"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("done");

        assertThat(snapshot.context()).containsKey("results");
        Map<String, Object> results = (Map<String, Object>) snapshot.context().get("results");
        assertThat(results).containsKey("process-a");
        assertThat(results).containsKey("process-b");
    }
}
