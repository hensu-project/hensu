package io.hensu.server.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.Plan.PlanSource;
import io.hensu.core.plan.PlanConstraints;
import io.hensu.core.plan.PlanCreationException;
import io.hensu.core.plan.PlanRevisionException;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.plan.Planner.PlanRequest;
import io.hensu.core.plan.Planner.RevisionContext;
import io.hensu.core.plan.StepResult;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolDefinition.ParameterDef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LlmPlannerTest {

    private Agent planningAgent;
    private ObjectMapper objectMapper;
    private LlmPlanner planner;

    @BeforeEach
    void setUp() {
        planningAgent = mock(Agent.class);
        objectMapper = new ObjectMapper();
        planner = new LlmPlanner(planningAgent, objectMapper);
    }

    @Nested
    class CreatePlan {

        @Test
        void shouldCreatePlanFromPlanProposalResponse() throws PlanCreationException {
            List<PlannedStep> proposedSteps =
                    List.of(
                            PlannedStep.pending(
                                    0, "search", Map.of("query", "test"), "Search for data"),
                            PlannedStep.pending(1, "process", Map.of(), "Process results"));

            AgentResponse response = AgentResponse.PlanProposal.of(proposedSteps, "Plan reasoning");
            when(planningAgent.execute(anyString(), any())).thenReturn(response);

            PlanRequest request =
                    new PlanRequest(
                            "Find and process data",
                            List.of(ToolDefinition.simple("search", "Search tool")),
                            Map.of(),
                            PlanConstraints.defaults());

            Plan plan = planner.createPlan(request);

            assertThat(plan.source()).isEqualTo(PlanSource.LLM_GENERATED);
            assertThat(plan.stepCount()).isEqualTo(2);
            assertThat(plan.getStep(0).toolName()).isEqualTo("search");
            assertThat(plan.getStep(1).toolName()).isEqualTo("process");
        }

        @Test
        void shouldCreatePlanFromJsonTextResponse() throws PlanCreationException {
            String jsonPlan =
                    """
                    [
                      {"tool": "fetch", "arguments": {"url": "http://api.com"}, "description": "Fetch data"},
                      {"tool": "save", "arguments": {"path": "/out"}, "description": "Save results"}
                    ]
                    """;

            AgentResponse response = AgentResponse.TextResponse.of(jsonPlan);
            when(planningAgent.execute(anyString(), any())).thenReturn(response);

            PlanRequest request = PlanRequest.simple("Fetch and save data");

            Plan plan = planner.createPlan(request);

            assertThat(plan.stepCount()).isEqualTo(2);
            assertThat(plan.getStep(0).toolName()).isEqualTo("fetch");
            assertThat(plan.getStep(0).arguments()).containsEntry("url", "http://api.com");
            assertThat(plan.getStep(1).toolName()).isEqualTo("save");
        }

        @Test
        void shouldExtractJsonFromCodeBlocks() throws PlanCreationException {
            String markdownResponse =
                    """
                    Here's the plan:

                    ```json
                    [
                      {"tool": "analyze", "arguments": {}, "description": "Analyze input"}
                    ]
                    ```

                    This plan will analyze the input.
                    """;

            AgentResponse response = AgentResponse.TextResponse.of(markdownResponse);
            when(planningAgent.execute(anyString(), any())).thenReturn(response);

            Plan plan = planner.createPlan(PlanRequest.simple("Analyze data"));

            assertThat(plan.stepCount()).isEqualTo(1);
            assertThat(plan.getStep(0).toolName()).isEqualTo("analyze");
        }

        @Test
        void shouldTruncatePlanExceedingMaxSteps() throws PlanCreationException {
            String jsonPlan =
                    """
                    [
                      {"tool": "step1", "description": "Step 1"},
                      {"tool": "step2", "description": "Step 2"},
                      {"tool": "step3", "description": "Step 3"},
                      {"tool": "step4", "description": "Step 4"},
                      {"tool": "step5", "description": "Step 5"}
                    ]
                    """;

            AgentResponse response = AgentResponse.TextResponse.of(jsonPlan);
            when(planningAgent.execute(anyString(), any())).thenReturn(response);

            PlanRequest request =
                    new PlanRequest(
                            "Multi-step task",
                            List.of(),
                            Map.of(),
                            PlanConstraints.defaults().withMaxSteps(3));

            Plan plan = planner.createPlan(request);

            assertThat(plan.stepCount()).isEqualTo(3);
        }

        @Test
        void shouldThrowOnEmptyPlan() {
            AgentResponse response = AgentResponse.TextResponse.of("[]");
            when(planningAgent.execute(anyString(), any())).thenReturn(response);

            assertThatThrownBy(() -> planner.createPlan(PlanRequest.simple("Goal")))
                    .isInstanceOf(PlanCreationException.class)
                    .hasMessageContaining("empty plan");
        }

        @Test
        void shouldThrowOnAgentError() {
            AgentResponse response = AgentResponse.Error.of("Model unavailable");
            when(planningAgent.execute(anyString(), any())).thenReturn(response);

            assertThatThrownBy(() -> planner.createPlan(PlanRequest.simple("Goal")))
                    .isInstanceOf(PlanCreationException.class)
                    .hasMessageContaining("Planning agent failed");
        }

        @Test
        void shouldThrowOnUnexpectedToolRequest() {
            AgentResponse response = AgentResponse.ToolRequest.of("unexpected_tool", Map.of());
            when(planningAgent.execute(anyString(), any())).thenReturn(response);

            assertThatThrownBy(() -> planner.createPlan(PlanRequest.simple("Goal")))
                    .isInstanceOf(PlanCreationException.class)
                    .hasMessageContaining("Unexpected tool request");
        }

        @Test
        void shouldThrowOnInvalidJson() {
            AgentResponse response = AgentResponse.TextResponse.of("not valid json at all");
            when(planningAgent.execute(anyString(), any())).thenReturn(response);

            assertThatThrownBy(() -> planner.createPlan(PlanRequest.simple("Goal")))
                    .isInstanceOf(PlanCreationException.class)
                    .hasMessageContaining("Failed to parse plan JSON");
        }

        @Test
        void shouldIncludeToolsInPrompt() throws PlanCreationException {
            AgentResponse response =
                    AgentResponse.TextResponse.of(
                            "[{\"tool\": \"search\", \"description\": \"x\"}]");
            when(planningAgent.execute(anyString(), any())).thenReturn(response);

            List<ToolDefinition> tools =
                    List.of(
                            ToolDefinition.of(
                                    "search",
                                    "Search the web",
                                    List.of(
                                            ParameterDef.required(
                                                    "query", "string", "Search query"))));

            planner.createPlan(
                    new PlanRequest("Search goal", tools, Map.of(), PlanConstraints.defaults()));

            verify(planningAgent)
                    .execute(
                            argThat(
                                    prompt ->
                                            prompt.contains("search")
                                                    && prompt.contains("Search the web")),
                            any());
        }
    }

    @Nested
    class RevisePlan {

        @Test
        void shouldRevisePlanAfterFailure() throws PlanRevisionException {
            Plan originalPlan =
                    Plan.dynamicPlan(
                            "node-1",
                            List.of(
                                    PlannedStep.pending(0, "fetch", Map.of(), "Fetch data"),
                                    PlannedStep.pending(1, "process", Map.of(), "Process data")));

            String revisedJson =
                    """
                    [
                      {"tool": "retry_fetch", "arguments": {"timeout": 30}, "description": "Retry with longer timeout"},
                      {"tool": "process", "arguments": {}, "description": "Process data"}
                    ]
                    """;

            AgentResponse response = AgentResponse.TextResponse.of(revisedJson);
            when(planningAgent.execute(anyString(), any())).thenReturn(response);

            StepResult failedResult =
                    StepResult.failure(0, "fetch", "Connection timeout", java.time.Duration.ZERO);
            RevisionContext context = RevisionContext.fromFailure(failedResult);

            Plan revisedPlan = planner.revisePlan(originalPlan, context);

            assertThat(revisedPlan.id()).isNotEqualTo(originalPlan.id());
            assertThat(revisedPlan.nodeId()).isEqualTo("node-1");
            assertThat(revisedPlan.stepCount()).isEqualTo(2);
            assertThat(revisedPlan.getStep(0).toolName()).isEqualTo("retry_fetch");
        }

        @Test
        void shouldThrowOnEmptyRevisedPlan() {
            Plan originalPlan =
                    Plan.dynamicPlan("node-1", List.of(PlannedStep.simple(0, "tool", "desc")));

            AgentResponse response = AgentResponse.TextResponse.of("[]");
            when(planningAgent.execute(anyString(), any())).thenReturn(response);

            StepResult failedResult =
                    StepResult.failure(0, "tool", "Error", java.time.Duration.ZERO);
            RevisionContext context = RevisionContext.fromFailure(failedResult);

            assertThatThrownBy(() -> planner.revisePlan(originalPlan, context))
                    .isInstanceOf(PlanRevisionException.class)
                    .hasMessageContaining("empty revised plan");
        }

        @Test
        void shouldThrowOnParseError() {
            Plan originalPlan =
                    Plan.dynamicPlan("node-1", List.of(PlannedStep.simple(0, "tool", "desc")));

            AgentResponse response = AgentResponse.TextResponse.of("invalid json");
            when(planningAgent.execute(anyString(), any())).thenReturn(response);

            StepResult failedResult =
                    StepResult.failure(0, "tool", "Error", java.time.Duration.ZERO);
            RevisionContext context = RevisionContext.fromFailure(failedResult);

            assertThatThrownBy(() -> planner.revisePlan(originalPlan, context))
                    .isInstanceOf(PlanRevisionException.class)
                    .hasMessageContaining("Failed to parse");
        }
    }

    @Nested
    class ConstructorValidation {

        @Test
        void shouldRejectNullAgent() {
            assertThatThrownBy(() -> new LlmPlanner(null, objectMapper))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("planningAgent");
        }

        @Test
        void shouldRejectNullObjectMapper() {
            assertThatThrownBy(() -> new LlmPlanner(planningAgent, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("objectMapper");
        }
    }
}
