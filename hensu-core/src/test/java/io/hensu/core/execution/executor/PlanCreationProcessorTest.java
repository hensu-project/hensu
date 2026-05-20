package io.hensu.core.execution.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.plan.*;
import io.hensu.core.state.HensuState;
import io.hensu.core.tool.ToolRegistry;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.StandardNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PlanCreationProcessor")
class PlanCreationProcessorTest {

    private Planner planner;
    private PlanCreationProcessor processor;

    @BeforeEach
    void setUp() {
        planner = mock(Planner.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.all()).thenReturn(List.of());
        processor = new PlanCreationProcessor(planner, toolRegistry);
    }

    @Nested
    @DisplayName("Resume with persisted plan")
    class ResumeWithPersistedPlan {

        @Test
        @DisplayName("reuses active plan and skips planner when nodeId matches")
        void shouldReusePlanAndSkipPlannerWhenNodeIdMatches() throws PlanCreationException {
            Plan persisted =
                    Plan.staticPlan(
                            "planning-node",
                            List.of(PlannedStep.pending(0, "tool-a", Map.of(), "step")));
            PlanContext ctx = dynamicPlanContext();
            ctx.executionContext().getState().setActivePlan(persisted);

            Optional<NodeResult> result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.getPlan()).isSameAs(persisted);
            verify(planner, never()).createPlan(any());
        }

        @Test
        @DisplayName("throws when active plan nodeId mismatches current node")
        void shouldThrowWhenActivePlanNodeIdMismatches() {
            Plan stale =
                    Plan.staticPlan(
                            "other-node",
                            List.of(PlannedStep.pending(0, "tool-a", Map.of(), "step")));
            PlanContext ctx = dynamicPlanContext();
            ctx.executionContext().getState().setActivePlan(stale);

            assertThatThrownBy(() -> processor.process(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("other-node")
                    .hasMessageContaining("planning-node");
        }
    }

    @Nested
    @DisplayName("Fresh plan creation")
    class FreshPlanCreation {

        @Test
        @DisplayName("persists created plan to state for checkpoint survival")
        void shouldPersistCreatedPlanToState() throws PlanCreationException {
            Plan created =
                    Plan.dynamicPlan(
                            "planning-node",
                            List.of(PlannedStep.pending(0, "tool-a", Map.of(), "step")));
            when(planner.createPlan(any())).thenReturn(created);

            PlanContext ctx = dynamicPlanContext();
            Optional<NodeResult> result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.getPlan()).isSameAs(created);
            assertThat(ctx.executionContext().getState().getActivePlan()).isSameAs(created);
        }

        @Test
        @DisplayName("does not persist plan to state on creation failure")
        void shouldNotPersistOnCreationFailure() throws PlanCreationException {
            when(planner.createPlan(any())).thenThrow(new PlanCreationException("boom"));

            PlanContext ctx = dynamicPlanContext();
            Optional<NodeResult> result = processor.process(ctx);

            assertThat(result).isPresent();
            assertThat(ctx.executionContext().getState().getActivePlan()).isNull();
        }
    }

    // — Helpers ———————————————————————————————————————————————————————————

    private PlanContext dynamicPlanContext() {
        StandardNode node =
                StandardNode.builder()
                        .id("planning-node")
                        .agentId("test-agent")
                        .prompt("test prompt")
                        .planningConfig(PlanningConfig.forDynamic())
                        .transitionRules(List.of())
                        .build();

        HensuState state =
                new HensuState.Builder()
                        .executionId("exec-1")
                        .workflowId("wf-1")
                        .currentNode("planning-node")
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .build();

        Workflow workflow =
                Workflow.builder()
                        .id("wf-1")
                        .startNode("planning-node")
                        .nodes(Map.of("planning-node", node))
                        .build();

        ExecutionContext execCtx =
                ExecutionContext.builder().state(state).workflow(workflow).build();
        return new PlanContext(node, execCtx);
    }
}
