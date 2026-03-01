package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.workflow.WorkflowService.ExecutionStartResult;
import io.hensu.server.workflow.WorkflowService.WorkflowNotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Integration tests for the full workflow lifecycle.
///
/// Covers push-then-execute, execution status retrieval, multi-tenant
/// isolation, and missing-workflow error handling.
///
/// ### Contracts
/// - **Precondition**: Stub mode enabled (`hensu.stub.enabled=true`)
/// - **Invariant**: All executions use {@link #TEST_TENANT} unless explicitly overridden
///
/// @see IntegrationTestBase for shared test infrastructure
/// @see io.hensu.server.workflow.WorkflowService for the service layer under test
@QuarkusTest
class WorkflowLifecycleIntegrationTest extends IntegrationTestBase {

    @Test
    void shouldPushWorkflowThenExecute() {
        Workflow workflow = loadWorkflow("standard-basic.json");
        registerStub("process", "Lifecycle test output for topic.");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("topic", "test"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, workflow.getId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.isCompleted()).isTrue();
    }

    @Test
    void shouldRetrieveExecutionStatus() {
        Workflow workflow = loadWorkflow("standard-basic.json");
        registerStub("process", "Status retrieval test output.");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("topic", "test"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, workflow.getId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.workflowId()).isEqualTo(workflow.getId());
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
    }

    @Test
    void shouldIsolateTenants() {
        Workflow workflow = loadWorkflow("standard-basic.json");
        workflowRepository.save(TEST_TENANT, workflow);

        assertThatThrownBy(
                        () ->
                                workflowService.startExecution(
                                        "other-tenant", workflow.getId(), Map.of()))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    @Test
    void shouldReturn404ForMissingWorkflow() {
        assertThatThrownBy(
                        () ->
                                workflowService.startExecution(
                                        TEST_TENANT, "non-existent-id", Map.of()))
                .isInstanceOf(WorkflowNotFoundException.class);
    }
}
