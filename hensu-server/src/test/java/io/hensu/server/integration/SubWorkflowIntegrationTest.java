package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.service.WorkflowService.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Integration tests for sub-workflow execution with input/output mapping.
///
/// Verifies that a parent workflow can invoke a child workflow, pass context
/// through input mapping, and receive results via output mapping.
///
/// @see io.hensu.core.workflow.node.SubWorkflowNode
/// @see io.hensu.core.execution.executor.SubWorkflowNodeExecutor
@QuarkusTest
class SubWorkflowIntegrationTest extends IntegrationTestBase {

    @Test
    void shouldExecuteSubWorkflowWithInputOutputMapping() {
        Workflow parent = loadWorkflow("sub-workflow-parent.json");
        Workflow child = loadWorkflow("sub-workflow-child.json");

        // Register both workflows so SubWorkflowNodeExecutor can find the child
        workflowRepository.save(TEST_TENANT, child);

        registerStub("prepare", "Prepared data for processing");
        registerStub("child-process", "Processed child result");

        ExecutionStartResult result =
                pushAndExecute(parent, Map.of("topic", "integration testing"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.context()).containsKey("child_result");
    }

    @Test
    void shouldFailWhenChildWorkflowNotFound() {
        Workflow parent = loadWorkflow("sub-workflow-parent.json");
        // Do NOT register the child workflow

        registerStub("prepare", "Prepared data for processing");

        ExecutionStartResult result =
                pushAndExecute(parent, Map.of("topic", "integration testing"));

        HensuSnapshot snapshot =
                workflowStateRepository
                        .findByExecutionId(TEST_TENANT, result.executionId())
                        .orElseThrow();
        assertThat(snapshot.checkpointReason()).isEqualTo("failed");
    }
}
