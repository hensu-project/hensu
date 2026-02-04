package io.hensu.server.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanCreationException;
import io.hensu.core.plan.PlanExecutor;
import io.hensu.core.plan.PlanObserver;
import io.hensu.core.plan.PlanResult;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.plan.PlanningConfig;
import io.hensu.core.state.HensuState;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolRegistry;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.server.planner.LlmPlanner;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AgenticNodeExecutorTest {

    private LlmPlanner llmPlanner;
    private PlanExecutor planExecutor;
    private ToolRegistry toolRegistry;
    private Instance<PlanObserver> observerInstance;
    private AgenticNodeExecutor executor;

    private StandardNode node;
    private ExecutionContext context;
    private AgentRegistry agentRegistry;
    private Agent agent;

    @BeforeEach
    void setUp() {
        llmPlanner = mock(LlmPlanner.class);
        planExecutor = mock(PlanExecutor.class);
        toolRegistry = mock(ToolRegistry.class);
        observerInstance = mock(Instance.class);
        when(observerInstance.stream()).thenReturn(Stream.empty());

        executor =
                new AgenticNodeExecutor(llmPlanner, planExecutor, toolRegistry, observerInstance);

        // Setup common mocks
        node = mock(StandardNode.class);
        when(node.getId()).thenReturn("test-node");
        when(node.getAgentId()).thenReturn("test-agent");
        when(node.getPrompt()).thenReturn("Test prompt");

        agentRegistry = mock(AgentRegistry.class);
        agent = mock(Agent.class);

        HensuState state = mock(HensuState.class);
        when(state.getContext()).thenReturn(Map.of());

        Workflow workflow = mock(Workflow.class);

        context =
                ExecutionContext.builder()
                        .state(state)
                        .workflow(workflow)
                        .agentRegistry(agentRegistry)
                        .build();
    }

    @Test
    void shouldReturnCorrectNodeType() {
        assertThat(executor.getNodeType()).isEqualTo(StandardNode.class);
    }

    @Nested
    class SimpleModeExecution {

        @Test
        void shouldExecuteSimpleAgentCallWhenPlanningDisabled() throws Exception {
            when(node.hasPlanningEnabled()).thenReturn(false);
            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(agent));
            when(agent.execute(anyString(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("Agent output"));

            NodeResult result = executor.execute(node, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).isEqualTo("Agent output");
            verify(llmPlanner, never()).createPlan(any());
        }

        @Test
        void shouldFailWhenAgentNotFound() throws Exception {
            when(node.hasPlanningEnabled()).thenReturn(false);
            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.empty());

            NodeResult result = executor.execute(node, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getOutput().toString()).contains("Agent not found");
        }

        @Test
        void shouldHandleAgentError() throws Exception {
            when(node.hasPlanningEnabled()).thenReturn(false);
            when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(agent));
            when(agent.execute(anyString(), any()))
                    .thenReturn(AgentResponse.Error.of("Agent failed"));

            NodeResult result = executor.execute(node, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getOutput().toString()).contains("Agent failed");
        }
    }

    @Nested
    class StaticPlanExecution {

        @Test
        void shouldExecuteStaticPlan() throws Exception {
            Plan staticPlan =
                    Plan.staticPlan(
                            "test-node", List.of(PlannedStep.simple(0, "tool", "Do something")));

            PlanningConfig config = PlanningConfig.forStatic();

            when(node.hasPlanningEnabled()).thenReturn(true);
            when(node.getPlanningConfig()).thenReturn(config);
            when(node.getStaticPlan()).thenReturn(staticPlan);
            when(planExecutor.execute(any(), any())).thenReturn(PlanResult.completed(List.of()));

            NodeResult result = executor.execute(node, context);

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void shouldFailWhenStaticPlanNotDefined() throws Exception {
            PlanningConfig config = PlanningConfig.forStatic();

            when(node.hasPlanningEnabled()).thenReturn(true);
            when(node.getPlanningConfig()).thenReturn(config);
            when(node.getStaticPlan()).thenReturn(null);

            NodeResult result = executor.execute(node, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getOutput().toString()).contains("Plan creation failed");
        }
    }

    @Nested
    class DynamicPlanExecution {

        @Test
        void shouldCreateAndExecuteDynamicPlan() throws Exception {
            PlanningConfig config = PlanningConfig.forDynamic();
            Plan dynamicPlan =
                    Plan.dynamicPlan(
                            "test-node",
                            List.of(PlannedStep.simple(0, "search", "Search for info")));

            when(node.hasPlanningEnabled()).thenReturn(true);
            when(node.getPlanningConfig()).thenReturn(config);
            when(toolRegistry.all())
                    .thenReturn(List.of(ToolDefinition.simple("search", "Search tool")));
            when(llmPlanner.createPlan(any())).thenReturn(dynamicPlan);
            when(planExecutor.execute(any(), any())).thenReturn(PlanResult.completed(List.of()));

            NodeResult result = executor.execute(node, context);

            assertThat(result.isSuccess()).isTrue();
            verify(llmPlanner).createPlan(any());
        }

        @Test
        void shouldHandlePlanCreationFailure() throws Exception {
            PlanningConfig config = PlanningConfig.forDynamic();

            when(node.hasPlanningEnabled()).thenReturn(true);
            when(node.getPlanningConfig()).thenReturn(config);
            when(toolRegistry.all()).thenReturn(List.of());
            when(llmPlanner.createPlan(any()))
                    .thenThrow(new PlanCreationException("LLM unavailable"));

            NodeResult result = executor.execute(node, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getOutput().toString()).contains("Plan creation failed");
        }
    }

    @Nested
    class PlanReview {

        @Test
        void shouldPauseForReviewWhenRequired() throws Exception {
            Plan staticPlan =
                    Plan.staticPlan(
                            "test-node", List.of(PlannedStep.simple(0, "tool", "Do something")));

            PlanningConfig config = PlanningConfig.forStaticWithReview();

            when(node.hasPlanningEnabled()).thenReturn(true);
            when(node.getPlanningConfig()).thenReturn(config);
            when(node.getStaticPlan()).thenReturn(staticPlan);

            NodeResult result = executor.execute(node, context);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.PENDING);
            assertThat(result.getMetadata()).containsEntry("_plan_review_required", true);
            verify(planExecutor, never()).execute(any(), any());
        }
    }

    @Nested
    class PlanFailureHandling {

        @Test
        void shouldUseFailureTargetWhenSet() throws Exception {
            Plan staticPlan =
                    Plan.staticPlan(
                            "test-node", List.of(PlannedStep.simple(0, "tool", "Do something")));

            PlanningConfig config = PlanningConfig.forStatic();

            when(node.hasPlanningEnabled()).thenReturn(true);
            when(node.getPlanningConfig()).thenReturn(config);
            when(node.getStaticPlan()).thenReturn(staticPlan);
            when(node.getPlanFailureTarget()).thenReturn("error-handler");
            when(planExecutor.execute(any(), any()))
                    .thenReturn(PlanResult.failed(List.of(), 0, "Step failed"));

            NodeResult result = executor.execute(node, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMetadata()).containsEntry("_plan_failure_target", "error-handler");
        }
    }
}
