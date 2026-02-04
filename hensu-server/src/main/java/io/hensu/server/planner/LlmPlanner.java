package io.hensu.server.planner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.Plan.PlanSource;
import io.hensu.core.plan.PlanCreationException;
import io.hensu.core.plan.PlanRevisionException;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.plan.Planner;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolDefinition.ParameterDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jboss.logging.Logger;

/// LLM-based planner that generates execution plans dynamically.
///
/// Uses a planning agent (LLM) to create step-by-step plans based on:
/// - The goal to achieve
/// - Available tools and their schemas
/// - Workflow context
/// - Constraints (max steps, max revisions)
///
/// ### Plan Generation
/// The planner prompts the LLM with structured instructions and parses
/// the response as either:
/// - {@link AgentResponse.PlanProposal}: Direct plan from agent
/// - {@link AgentResponse.TextResponse}: JSON that needs parsing
///
/// ### Revision Support
/// Unlike {@link io.hensu.core.plan.StaticPlanner}, this planner supports
/// plan revision when steps fail, up to the configured limit.
///
/// ### Thread Safety
/// @implNote Thread-safe. The planner is stateless; all state is passed
/// through method parameters.
///
/// ### Usage
/// {@snippet :
/// Agent planningAgent = agentRegistry.get("planner");
/// LlmPlanner planner = new LlmPlanner(planningAgent, objectMapper);
///
/// Plan plan = planner.createPlan(new PlanRequest(
///     "Send order confirmation email",
///     availableTools,
///     Map.of("orderId", "123"),
///     PlanConstraints.defaults()
/// ));
/// }
///
/// @see Planner for the contract
/// @see io.hensu.core.plan.PlanExecutor for plan execution
public class LlmPlanner implements Planner {

    private static final Logger LOG = Logger.getLogger(LlmPlanner.class);

    private static final String PLANNING_PROMPT_TEMPLATE =
            """
            You are a planning agent. Create a step-by-step execution plan.

            ## Goal
            %s

            ## Available Tools
            %s

            ## Context
            %s

            ## Instructions
            Create a plan to achieve the goal using only the available tools.
            Output your plan as a JSON array of steps:

            ```json
            [
              {"tool": "tool_name", "arguments": {"param": "value"}, "description": "Why this step"},
              {"tool": "another_tool", "arguments": {}, "description": "Next step reason"}
            ]
            ```

            Rules:
            - Use only tools from the available list
            - Reference context values with {variable} syntax in arguments
            - Keep descriptions concise but informative
            - Maximum %d steps allowed
            """;

    private static final String REVISION_PROMPT_TEMPLATE =
            """
            You are a planning agent. A previous plan failed and needs revision.

            ## Original Goal
            %s

            ## Failed Plan
            %s

            ## Failure Details
            Step %d failed: %s

            ## Available Tools
            %s

            ## Instructions
            Revise the plan to handle the failure. You can:
            - Retry the failed step with different arguments
            - Add error recovery steps
            - Skip to alternative approaches

            Output the revised plan as JSON:

            ```json
            [
              {"tool": "tool_name", "arguments": {"param": "value"}, "description": "Why this step"}
            ]
            ```
            """;

    private final Agent planningAgent;
    private final ObjectMapper objectMapper;

    /// Creates an LLM planner with the specified planning agent.
    ///
    /// @param planningAgent the agent to use for plan generation, not null
    /// @param objectMapper JSON mapper for parsing responses, not null
    public LlmPlanner(Agent planningAgent, ObjectMapper objectMapper) {
        this.planningAgent =
                Objects.requireNonNull(planningAgent, "planningAgent must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public Plan createPlan(PlanRequest request) throws PlanCreationException {
        Objects.requireNonNull(request, "request must not be null");

        LOG.debugv("Creating plan for goal: {0}", request.goal());

        String prompt = buildPlanningPrompt(request);
        AgentResponse response = planningAgent.execute(prompt, request.context());

        List<PlannedStep> steps = extractSteps(response);

        if (steps.isEmpty()) {
            throw new PlanCreationException("LLM generated empty plan");
        }

        int maxSteps = request.constraints().maxSteps();
        if (steps.size() > maxSteps) {
            LOG.warnv("Plan has {0} steps but max is {1}, truncating", steps.size(), maxSteps);
            steps = steps.subList(0, maxSteps);
        }

        Plan plan =
                new Plan(
                        UUID.randomUUID().toString(),
                        "", // nodeId set by caller
                        PlanSource.LLM_GENERATED,
                        steps,
                        request.constraints());

        LOG.infov("Created plan with {0} steps", plan.stepCount());
        return plan;
    }

    @Override
    public Plan revisePlan(Plan currentPlan, RevisionContext context) throws PlanRevisionException {
        Objects.requireNonNull(currentPlan, "currentPlan must not be null");
        Objects.requireNonNull(context, "context must not be null");

        LOG.debugv("Revising plan {0} at step {1}", currentPlan.id(), context.failedAtStep());

        String prompt = buildRevisionPrompt(currentPlan, context);
        AgentResponse response = planningAgent.execute(prompt, Map.of());

        List<PlannedStep> steps;
        try {
            steps = extractSteps(response);
        } catch (PlanCreationException e) {
            throw new PlanRevisionException("Failed to parse revised plan: " + e.getMessage(), e);
        }

        if (steps.isEmpty()) {
            throw new PlanRevisionException("LLM generated empty revised plan");
        }

        Plan revisedPlan =
                new Plan(
                        UUID.randomUUID().toString(),
                        currentPlan.nodeId(),
                        PlanSource.LLM_GENERATED,
                        steps,
                        currentPlan.constraints());

        LOG.infov("Revised plan with {0} steps", revisedPlan.stepCount());
        return revisedPlan;
    }

    private String buildPlanningPrompt(PlanRequest request) {
        return PLANNING_PROMPT_TEMPLATE.formatted(
                request.goal(),
                formatTools(request.availableTools()),
                formatContext(request.context()),
                request.constraints().maxSteps());
    }

    private String buildRevisionPrompt(Plan currentPlan, RevisionContext context) {
        return REVISION_PROMPT_TEMPLATE.formatted(
                "", // Goal not stored in Plan, would need enhancement
                formatPlanSteps(currentPlan.steps()),
                context.failedAtStep(),
                context.revisionReason(),
                "" // Would need tools from original request
                );
    }

    private String formatTools(List<ToolDefinition> tools) {
        if (tools.isEmpty()) {
            return "(No tools available)";
        }

        StringBuilder sb = new StringBuilder();
        for (ToolDefinition tool : tools) {
            sb.append("- **").append(tool.name()).append("**: ").append(tool.description());
            if (!tool.parameters().isEmpty()) {
                sb.append("\n  Parameters: ");
                List<String> params = new ArrayList<>();
                for (ParameterDef param : tool.parameters()) {
                    String spec = param.name() + " (" + param.type();
                    if (param.required()) {
                        spec += ", required";
                    }
                    spec += ")";
                    params.add(spec);
                }
                sb.append(String.join(", ", params));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String formatContext(Map<String, Object> context) {
        if (context.isEmpty()) {
            return "(No context provided)";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            sb.append("- ")
                    .append(entry.getKey())
                    .append(": ")
                    .append(entry.getValue())
                    .append("\n");
        }
        return sb.toString();
    }

    private String formatPlanSteps(List<PlannedStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (PlannedStep step : steps) {
            sb.append(step.index())
                    .append(". ")
                    .append(step.toolName())
                    .append(": ")
                    .append(step.description())
                    .append(" [")
                    .append(step.status())
                    .append("]\n");
        }
        return sb.toString();
    }

    private List<PlannedStep> extractSteps(AgentResponse response) throws PlanCreationException {
        return switch (response) {
            case AgentResponse.PlanProposal proposal -> {
                LOG.debug("Received PlanProposal response");
                yield proposal.steps();
            }
            case AgentResponse.TextResponse text -> {
                LOG.debug("Received TextResponse, parsing JSON");
                yield parseStepsFromJson(text.content());
            }
            case AgentResponse.Error error ->
                    throw new PlanCreationException("Planning agent failed: " + error.message());
            case AgentResponse.ToolRequest toolRequest ->
                    throw new PlanCreationException(
                            "Unexpected tool request from planning agent: "
                                    + toolRequest.toolName());
        };
    }

    private List<PlannedStep> parseStepsFromJson(String content) throws PlanCreationException {
        // Extract JSON from markdown code blocks if present
        String json = extractJson(content);

        try {
            List<StepDto> dtos = objectMapper.readValue(json, new TypeReference<>() {});
            List<PlannedStep> steps = new ArrayList<>(dtos.size());

            for (int i = 0; i < dtos.size(); i++) {
                StepDto dto = dtos.get(i);
                steps.add(
                        PlannedStep.pending(
                                i,
                                dto.tool(),
                                dto.arguments() != null ? dto.arguments() : Map.of(),
                                dto.description() != null ? dto.description() : ""));
            }

            return steps;
        } catch (JsonProcessingException e) {
            throw new PlanCreationException("Failed to parse plan JSON: " + e.getMessage(), e);
        }
    }

    private String extractJson(String content) {
        // Look for JSON in code blocks
        int start = content.indexOf("```json");
        if (start >= 0) {
            start = content.indexOf('\n', start) + 1;
            int end = content.indexOf("```", start);
            if (end > start) {
                return content.substring(start, end).trim();
            }
        }

        // Look for JSON in generic code blocks
        start = content.indexOf("```");
        if (start >= 0) {
            start = content.indexOf('\n', start) + 1;
            int end = content.indexOf("```", start);
            if (end > start) {
                return content.substring(start, end).trim();
            }
        }

        // Try to find array brackets directly
        int arrayStart = content.indexOf('[');
        int arrayEnd = content.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return content.substring(arrayStart, arrayEnd + 1);
        }

        return content.trim();
    }

    /// DTO for JSON parsing of plan steps.
    private record StepDto(String tool, Map<String, Object> arguments, String description) {}
}
