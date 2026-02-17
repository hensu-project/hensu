package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.service.WorkflowService.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Integration tests for plan execution: static and dynamic planning modes.
///
/// Covers static plan step dispatch via {@link io.hensu.core.plan.PlanExecutor}
/// and dynamic plan generation via {@link io.hensu.server.planner.LlmPlanner} with
/// the auto-registered `_planning_agent`.
///
/// ### Contracts
/// - **Precondition**: Stub mode enabled (`hensu.stub.enabled=true`)
/// - **Invariant**: All executions use {@link #TEST_TENANT}
///
/// @see IntegrationTestBase for shared test infrastructure
/// @see TestActionHandler for the `"test-tool"` action handler
/// @see io.hensu.core.plan.PlanExecutor for static plan step execution
/// @see io.hensu.server.planner.LlmPlanner for dynamic plan generation
@QuarkusTest
class PlanExecutionIntegrationTest extends IntegrationTestBase {

    @Inject TestActionHandler testActionHandler;

    @Inject ActionExecutor actionExecutor;

    /// Resets the test action handler and ensures it is registered before each test.
    @BeforeEach
    void resetActionHandler() {
        testActionHandler.reset();
        actionExecutor.registerHandler(testActionHandler);
    }

    /// Verifies that a static plan with pre-defined steps dispatches each step
    /// to the correct action handler in order.
    ///
    /// The `plan-static.json` workflow defines two static steps, both targeting
    /// `"test-tool"`: the first with `action=search` and the second with
    /// `action=process`. The test confirms both payloads arrive at
    /// {@link TestActionHandler} in the expected order.
    @Test
    void shouldExecuteStaticPlan() {
        Workflow workflow = loadWorkflow("plan-static.json");
        registerStub("execute", "Plan execution complete");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("task", "test task"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");

        List<Map<String, Object>> payloads = testActionHandler.getReceivedPayloads();
        assertThat(payloads).hasSize(2);
        assertThat(payloads.getFirst()).containsEntry("action", "search");
        assertThat(payloads.get(1)).containsEntry("action", "process");
    }

    /// Verifies that dynamic planning generates a plan via the `_planning_agent`
    /// and executes the resulting steps through the action handler.
    ///
    /// The `plan-dynamic.json` workflow uses `planningConfig(mode=DYNAMIC)`.
    /// The stub for `_planning_agent` returns a JSON array with one step targeting
    /// `"test-tool"`. The test confirms that at least one payload is dispatched
    /// to {@link TestActionHandler}.
    @Test
    void shouldExecuteDynamicPlan() {
        Workflow workflow = loadWorkflow("plan-dynamic.json");
        registerStub("execute", "Dynamic execution complete");
        registerStub(
                "_planning_agent",
                "[{\"tool\":\"test-tool\",\"arguments\":{\"action\":\"fetch\"},\"description\":\"Fetch data\"}]");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of("task", "dynamic task"));

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");

        List<Map<String, Object>> payloads = testActionHandler.getReceivedPayloads();
        assertThat(payloads).hasSizeGreaterThanOrEqualTo(1);
    }
}
