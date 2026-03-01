package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.workflow.WorkflowService;
import io.hensu.server.workflow.WorkflowService.ExecutionStartResult;
import io.hensu.server.workflow.WorkflowService.ResumeDecision;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Integration tests for workflow state persistence across pause/resume cycles.
///
/// Verifies that a workflow can pause mid-execution, persist its state as a
/// snapshot, and resume from that checkpoint — potentially on a different
/// server instance (simulated by clearing in-memory repositories).
///
/// @see io.hensu.core.execution.result.ExecutionResult.Paused
/// @see WorkflowService#resumeExecution
/// @see TestPauseHandler
@QuarkusTest
class StatePersistenceIntegrationTest extends IntegrationTestBase {

    @Inject TestPauseHandler pauseHandler;

    @BeforeEach
    void resetPauseHandler() {
        pauseHandler.reset();
    }

    @Test
    void shouldPauseAndResumeOnDifferentInstance() {
        Workflow workflow = loadWorkflow("pause-resume.json");
        registerStub("step-a", "Step A output");
        registerStub("step-b", "Step B output");

        // Phase 1: Execute until pause
        ExecutionStartResult result = pushAndExecute(workflow, Map.of("input", "test data"));

        // Verify snapshot saved at pause point
        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot pausedSnapshot = snapshots.getLast();
        assertThat(pausedSnapshot.checkpointReason()).isEqualTo("paused");
        assertThat(pausedSnapshot.currentNodeId()).isEqualTo("pause-point");
        assertThat(pausedSnapshot.context()).containsEntry("step-a", "Step A output");

        // Phase 2: Resume — simulates a different server instance by using the
        // persisted snapshot. The TestPauseHandler returns SUCCESS on second call.
        workflowService.resumeExecution(
                TEST_TENANT, pausedSnapshot.executionId(), ResumeDecision.approve());

        // Verify final snapshot shows completion with both step outputs.
        // The repository stores one snapshot per execution ID — the completed
        // snapshot replaces the paused one.
        HensuSnapshot completedSnapshot =
                workflowStateRepository
                        .findByExecutionId(TEST_TENANT, pausedSnapshot.executionId())
                        .orElseThrow();
        assertThat(completedSnapshot.checkpointReason()).isEqualTo("completed");
        assertThat(completedSnapshot.context()).containsEntry("step-a", "Step A output");
        assertThat(completedSnapshot.context()).containsKey("step-b");
    }

    @Test
    void shouldPreserveExecutionIdAcrossResume() {
        Workflow workflow = loadWorkflow("pause-resume.json");
        registerStub("step-a", "Step A output");
        registerStub("step-b", "Step B output");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("input", "test data"));

        Optional<HensuSnapshot> paused =
                workflowStateRepository.findByExecutionId(TEST_TENANT, result.executionId());
        assertThat(paused).isPresent();
        assertThat(paused.get().checkpointReason()).isEqualTo("paused");

        // Resume
        workflowService.resumeExecution(
                TEST_TENANT, result.executionId(), ResumeDecision.approve());

        // The completed snapshot should preserve the same workflow ID
        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        HensuSnapshot completed = snapshots.getLast();
        assertThat(completed.checkpointReason()).isEqualTo("completed");
        assertThat(completed.workflowId()).isEqualTo(result.workflowId());
    }

    @Test
    void shouldApplyResumeModifications() {
        Workflow workflow = loadWorkflow("pause-resume.json");
        registerStub("step-a", "Step A output");
        registerStub("step-b", "Step B output");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("input", "test data"));

        // Resume with injected modifications
        workflowService.resumeExecution(
                TEST_TENANT,
                result.executionId(),
                ResumeDecision.modify(Map.of("extra", "injected-value")));

        // Verify the injected key is present in the final state
        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        HensuSnapshot completed = snapshots.getLast();
        assertThat(completed.checkpointReason()).isEqualTo("completed");
        assertThat(completed.context()).containsEntry("extra", "injected-value");
    }
}
