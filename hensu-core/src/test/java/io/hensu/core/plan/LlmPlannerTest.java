package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.plan.Planner.PlanRequest;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolDefinition.ParameterDef;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LlmPlannerTest {

    private Agent planningAgent;
    private PlanResponseParser responseParser;
    private LlmPlanner planner;

    @BeforeEach
    void setUp() {
        planningAgent = mock(Agent.class);
        responseParser = mock(PlanResponseParser.class);
        planner = new LlmPlanner(planningAgent, responseParser);
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

            when(planningAgent.execute(anyString(), any()))
                    .thenReturn(AgentResponse.PlanProposal.of(proposedSteps, "Plan reasoning"));

            PlanRequest request =
                    new PlanRequest(
                            "Find and process data",
                            List.of(ToolDefinition.simple("search", "Search tool")),
                            Map.of(),
                            PlanConstraints.defaults());

            Plan plan = planner.createPlan(request);

            assertThat(plan.source()).isEqualTo(Plan.PlanSource.LLM_GENERATED);
            assertThat(plan.stepCount()).isEqualTo(2);
            assertThat(plan.getStep(0).toolName()).isEqualTo("search");
            assertThat(plan.getStep(1).toolName()).isEqualTo("process");
        }

        @Test
        void shouldDelegateTextResponseToParser() throws PlanCreationException {
            String rawText = "some llm text response";
            List<PlannedStep> parsedSteps =
                    List.of(
                            PlannedStep.pending(
                                    0, "fetch", Map.of("url", "http://api.com"), "Fetch data"),
                            PlannedStep.pending(1, "save", Map.of("path", "/out"), "Save results"));

            when(planningAgent.execute(anyString(), any()))
                    .thenReturn(AgentResponse.TextResponse.of(rawText));
            when(responseParser.parse(rawText)).thenReturn(parsedSteps);

            Plan plan = planner.createPlan(PlanRequest.simple("Fetch and save data"));

            verify(responseParser).parse(rawText);
            assertThat(plan.stepCount()).isEqualTo(2);
            assertThat(plan.getStep(0).toolName()).isEqualTo("fetch");
            assertThat(plan.getStep(1).toolName()).isEqualTo("save");
        }

        @Test
        void shouldTruncatePlanExceedingMaxSteps() throws PlanCreationException {
            List<PlannedStep> fiveSteps =
                    List.of(
                            PlannedStep.simple(0, "step1", "Step 1"),
                            PlannedStep.simple(1, "step2", "Step 2"),
                            PlannedStep.simple(2, "step3", "Step 3"),
                            PlannedStep.simple(3, "step4", "Step 4"),
                            PlannedStep.simple(4, "step5", "Step 5"));

            when(planningAgent.execute(anyString(), any()))
                    .thenReturn(AgentResponse.PlanProposal.of(fiveSteps, ""));

            Plan plan =
                    planner.createPlan(
                            new PlanRequest(
                                    "Multi-step task",
                                    List.of(),
                                    Map.of(),
                                    PlanConstraints.defaults().withMaxSteps(3)));

            assertThat(plan.stepCount()).isEqualTo(3);
        }

        @Test
        void shouldThrowOnEmptyPlan() throws PlanCreationException {
            when(planningAgent.execute(anyString(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("[]"));
            when(responseParser.parse("[]")).thenReturn(List.of());

            assertThatThrownBy(() -> planner.createPlan(PlanRequest.simple("Goal")))
                    .isInstanceOf(PlanCreationException.class)
                    .hasMessageContaining("empty plan");
        }

        @Test
        void shouldThrowOnAgentError() {
            when(planningAgent.execute(anyString(), any()))
                    .thenReturn(AgentResponse.Error.of("Model unavailable"));

            assertThatThrownBy(() -> planner.createPlan(PlanRequest.simple("Goal")))
                    .isInstanceOf(PlanCreationException.class)
                    .hasMessageContaining("Planning agent failed");
        }

        @Test
        void shouldThrowOnUnexpectedToolRequest() {
            when(planningAgent.execute(anyString(), any()))
                    .thenReturn(AgentResponse.ToolRequest.of("unexpected_tool", Map.of()));

            assertThatThrownBy(() -> planner.createPlan(PlanRequest.simple("Goal")))
                    .isInstanceOf(PlanCreationException.class)
                    .hasMessageContaining("Unexpected tool request");
        }

        @Test
        void shouldIncludeToolsInPrompt() throws PlanCreationException {
            List<PlannedStep> steps = List.of(PlannedStep.simple(0, "search", "x"));
            when(planningAgent.execute(anyString(), any()))
                    .thenReturn(AgentResponse.PlanProposal.of(steps, ""));

            planner.createPlan(
                    new PlanRequest(
                            "Search goal",
                            List.of(
                                    ToolDefinition.of(
                                            "search",
                                            "Search the web",
                                            List.of(
                                                    ParameterDef.required(
                                                            "query", "string", "Search query")))),
                            Map.of(),
                            PlanConstraints.defaults()));

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
        void shouldRevisePlanAfterFailure() throws Exception {
            Plan originalPlan =
                    Plan.dynamicPlan(
                            "node-1",
                            List.of(
                                    PlannedStep.pending(0, "fetch", Map.of(), "Fetch data"),
                                    PlannedStep.pending(1, "process", Map.of(), "Process data")));

            String rawText = "revised json";
            List<PlannedStep> revisedSteps =
                    List.of(
                            PlannedStep.simple(0, "retry_fetch", "Retry with longer timeout"),
                            PlannedStep.simple(1, "process", "Process data"));

            when(planningAgent.execute(anyString(), any()))
                    .thenReturn(AgentResponse.TextResponse.of(rawText));
            when(responseParser.parse(rawText)).thenReturn(revisedSteps);

            StepResult failedResult =
                    StepResult.failure(0, "fetch", "Connection timeout", Duration.ZERO);
            Planner.RevisionContext context =
                    Planner.RevisionContext.fromFailure(
                            failedResult, "Fetch and process data", List.of());

            Plan revisedPlan = planner.revisePlan(originalPlan, context);

            assertThat(revisedPlan.id()).isNotEqualTo(originalPlan.id());
            assertThat(revisedPlan.nodeId()).isEqualTo("node-1");
            assertThat(revisedPlan.stepCount()).isEqualTo(2);
            assertThat(revisedPlan.getStep(0).toolName()).isEqualTo("retry_fetch");
        }

        @Test
        void shouldThrowOnEmptyRevisedPlan() throws PlanCreationException {
            Plan originalPlan =
                    Plan.dynamicPlan("node-1", List.of(PlannedStep.simple(0, "tool", "desc")));

            when(planningAgent.execute(anyString(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("[]"));
            when(responseParser.parse("[]")).thenReturn(List.of());

            StepResult failedResult = StepResult.failure(0, "tool", "Error", Duration.ZERO);
            Planner.RevisionContext context =
                    Planner.RevisionContext.fromFailure(failedResult, "Process data", List.of());

            assertThatThrownBy(() -> planner.revisePlan(originalPlan, context))
                    .isInstanceOf(PlanRevisionException.class)
                    .hasMessageContaining("empty revised plan");
        }

        @Test
        void shouldWrapParserExceptionInRevisionException() throws PlanCreationException {
            Plan originalPlan =
                    Plan.dynamicPlan("node-1", List.of(PlannedStep.simple(0, "tool", "desc")));

            when(planningAgent.execute(anyString(), any()))
                    .thenReturn(AgentResponse.TextResponse.of("invalid"));
            when(responseParser.parse("invalid"))
                    .thenThrow(new PlanCreationException("Failed to parse plan JSON"));

            StepResult failedResult = StepResult.failure(0, "tool", "Error", Duration.ZERO);
            Planner.RevisionContext context =
                    Planner.RevisionContext.fromFailure(failedResult, "Process data", List.of());

            assertThatThrownBy(() -> planner.revisePlan(originalPlan, context))
                    .isInstanceOf(PlanRevisionException.class)
                    .hasMessageContaining("Failed to parse");
        }
    }

    @Nested
    class ConstructorValidation {

        @Test
        void shouldRejectNullAgent() {
            assertThatThrownBy(() -> new LlmPlanner(null, responseParser))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("planningAgent");
        }

        @Test
        void shouldRejectNullResponseParser() {
            assertThatThrownBy(() -> new LlmPlanner(planningAgent, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("responseParser");
        }
    }
}
