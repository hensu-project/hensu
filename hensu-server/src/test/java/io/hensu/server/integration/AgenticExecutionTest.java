package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.plan.PlanningConfig;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.rubric.evaluator.DefaultRubricEvaluator;
import io.hensu.core.storage.rubric.InMemoryRubricRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/// Integration tests for agentic workflow execution.
///
/// Tests the full execution flow including:
/// - Static plan execution
/// - Dynamic plan generation and execution
/// - Plan review workflow
@QuarkusTest
class AgenticExecutionTest {

    @Inject NodeExecutorRegistry nodeExecutorRegistry;

    private WorkflowExecutor workflowExecutor;
    private AgentRegistry agentRegistry;
    private ActionExecutor actionExecutor;
    private Agent mockAgent;

    @BeforeEach
    void setUp() {
        // Setup mock agent
        mockAgent = mock(Agent.class);
        when(mockAgent.getId()).thenReturn("test-agent");
        when(mockAgent.getConfig()).thenReturn(null);

        // Setup mock agent registry
        agentRegistry = mock(AgentRegistry.class);
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(agentRegistry.hasAgent("test-agent")).thenReturn(true);

        // Setup mock action executor for tool calls
        actionExecutor = mock(ActionExecutor.class);
        when(actionExecutor.execute(any(Action.class), any()))
                .thenReturn(ActionResult.success("Tool executed", Map.of("result", "success")));

        // Create workflow executor with test dependencies
        RubricEngine rubricEngine =
                new RubricEngine(new InMemoryRubricRepository(), new DefaultRubricEvaluator());

        workflowExecutor =
                new WorkflowExecutor(
                        nodeExecutorRegistry,
                        agentRegistry,
                        Executors.newVirtualThreadPerTaskExecutor(),
                        rubricEngine,
                        null,
                        actionExecutor);
    }

    @Test
    void shouldExecuteSimpleWorkflowWithoutPlanning() throws Exception {
        // Given: workflow with planning disabled
        when(mockAgent.execute(anyString(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Task completed successfully"));

        Workflow workflow = createSimpleWorkflow(false);

        // When: execute workflow
        ExecutionResult result = workflowExecutor.execute(workflow, Map.of("input", "test"));

        // Then: workflow completes successfully
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
        assertThat(completed.getFinalState().getContext()).containsKey("process");
    }

    @Test
    @Disabled("Requires full AgenticNodeExecutor registration - test shows intended behavior")
    void shouldExecuteStaticPlan() throws Exception {
        // Given: workflow with static plan
        Plan staticPlan =
                Plan.staticPlan(
                        "process",
                        List.of(
                                PlannedStep.simple(0, "get_data", "Fetch data"),
                                PlannedStep.simple(1, "transform", "Transform data"),
                                PlannedStep.simple(2, "store", "Store result")));

        Workflow workflow = createWorkflowWithStaticPlan(staticPlan);

        // When: execute with action executor handling tool calls
        ExecutionResult result = workflowExecutor.execute(workflow, Map.of("id", "123"));

        // Then: all steps executed in order
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    @Disabled("Requires LLM planner mock - test shows intended behavior")
    void shouldExecuteDynamicPlan() throws Exception {
        // Given: workflow with dynamic planning
        // Mock LLM to return a plan
        Plan dynamicPlan =
                Plan.dynamicPlan(
                        "research",
                        List.of(
                                PlannedStep.simple(0, "search", "Search for information"),
                                PlannedStep.simple(1, "analyze", "Analyze results")));

        Workflow workflow = createWorkflowWithDynamicPlanning();

        // When: execute with mock LLM that returns plan
        ExecutionResult result = workflowExecutor.execute(workflow, Map.of("topic", "AI"));

        // Then: LLM-generated plan executed
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    /// Creates a simple workflow without planning.
    private Workflow createSimpleWorkflow(boolean planningEnabled) {
        StandardNode processNode =
                StandardNode.builder()
                        .id("process")
                        .agentId("test-agent")
                        .prompt("Process the input: {input}")
                        .planningConfig(
                                planningEnabled
                                        ? PlanningConfig.forStatic()
                                        : PlanningConfig.disabled())
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();

        EndNode endNode = EndNode.builder().id("end").status(ExitStatus.SUCCESS).build();

        return Workflow.builder()
                .id("test-workflow")
                .startNode("process")
                .nodes(Map.of("process", processNode, "end", endNode))
                .build();
    }

    /// Creates a workflow with a static plan.
    private Workflow createWorkflowWithStaticPlan(Plan staticPlan) {
        StandardNode processNode =
                StandardNode.builder()
                        .id("process")
                        .agentId("test-agent")
                        .prompt("Process order {id}")
                        .planningConfig(PlanningConfig.forStatic())
                        .staticPlan(staticPlan)
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();

        EndNode endNode = EndNode.builder().id("end").status(ExitStatus.SUCCESS).build();

        return Workflow.builder()
                .id("order-workflow")
                .startNode("process")
                .nodes(Map.of("process", processNode, "end", endNode))
                .build();
    }

    /// Creates a workflow with dynamic planning enabled.
    private Workflow createWorkflowWithDynamicPlanning() {
        StandardNode researchNode =
                StandardNode.builder()
                        .id("research")
                        .agentId("test-agent")
                        .prompt("Research {topic} comprehensively")
                        .planningConfig(PlanningConfig.forDynamic())
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();

        EndNode endNode = EndNode.builder().id("end").status(ExitStatus.SUCCESS).build();

        return Workflow.builder()
                .id("research-workflow")
                .startNode("research")
                .nodes(Map.of("research", researchNode, "end", endNode))
                .build();
    }
}
