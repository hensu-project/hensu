package io.hensu.core.plan;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolDefinition.ParameterDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/// LLM-based {@link Planner} that generates execution plans dynamically.
///
/// Uses a planning agent to create step-by-step plans based on the goal,
/// available tools, and workflow context. Supports both tool-call steps and
/// synthesize steps, enabling the agent to express "call this tool, then
/// summarise the results" within a single plan.
///
/// ### Plan Generation
/// The planner prompts the LLM with structured instructions and parses the
/// response via the supplied {@link PlanResponseParser}. The agent may return:
/// - {@link AgentResponse.PlanProposal}: structured step list (used as-is)
/// - {@link AgentResponse.TextResponse}: JSON that the parser extracts from
///   the response text
///
/// ### Synthesize Steps
/// The prompt instructs the LLM that it may optionally add a final synthesis
/// step. The {@code agentId} on such steps is left {@code null}; the calling
/// executor enriches it with the node's own agent before execution begins.
///
/// ### Revision Support
/// Unlike {@link StaticPlanner}, this planner supports plan revision when steps
/// fail, up to the configured limit in {@link PlanConstraints#maxReplans()}.
///
/// @implNote Thread-safe. The planner is stateless; all state is passed
/// through method parameters.
///
/// @see Planner for the interface contract
/// @see PlanResponseParser for response parsing
/// @see io.hensu.core.execution.executor.AgenticNodeExecutor for the executor
public class LlmPlanner implements Planner {

    private static final Logger LOG = Logger.getLogger(LlmPlanner.class.getName());

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
            Create a plan to achieve the goal using the available tools.
            Output your plan as a JSON array of steps:

            ```json
            [
              {"tool": "tool_name", "arguments": {"param": "value"}, "description": "Why this step"},
              {"tool": "another_tool", "arguments": {}, "description": "Next step reason"}
            ]
            ```

            Optionally, add a final step to synthesise all results:

            ```json
            {"synthesize": true, "description": "Summarise all collected data into a final answer"}
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
            - Add a synthesize step at the end if useful

            Output the revised plan as JSON:

            ```json
            [
              {"tool": "tool_name", "arguments": {"param": "value"}, "description": "Why this step"},
              {"synthesize": true, "description": "Synthesise all results"}
            ]
            ```
            """;

    private final Agent planningAgent;
    private final PlanResponseParser responseParser;

    /// Creates an LLM planner.
    ///
    /// @param planningAgent the agent to use for plan generation, not null
    /// @param responseParser parser that converts the agent's text to steps, not null
    public LlmPlanner(Agent planningAgent, PlanResponseParser responseParser) {
        this.planningAgent =
                Objects.requireNonNull(planningAgent, "planningAgent must not be null");
        this.responseParser =
                Objects.requireNonNull(responseParser, "responseParser must not be null");
    }

    @Override
    public Plan createPlan(PlanRequest request) throws PlanCreationException {
        Objects.requireNonNull(request, "request must not be null");

        String prompt = buildPlanningPrompt(request);
        AgentResponse response = planningAgent.execute(prompt, request.context());

        List<PlannedStep> steps = extractSteps(response);

        if (steps.isEmpty()) {
            throw new PlanCreationException("LLM generated empty plan");
        }

        int maxSteps = request.constraints().maxSteps();
        if (steps.size() > maxSteps) {
            LOG.warning(
                    String.format(
                            "Plan has %d steps but max is %d, truncating", steps.size(), maxSteps));
            steps = steps.subList(0, maxSteps);
        }

        Plan plan =
                new Plan(
                        UUID.randomUUID().toString(),
                        "", // nodeId set by caller
                        Plan.PlanSource.LLM_GENERATED,
                        steps,
                        request.constraints());

        LOG.info("Created plan with " + plan.stepCount() + " steps");
        return plan;
    }

    @Override
    public Plan revisePlan(Plan currentPlan, RevisionContext context) throws PlanRevisionException {
        Objects.requireNonNull(currentPlan, "currentPlan must not be null");
        Objects.requireNonNull(context, "context must not be null");

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
                        Plan.PlanSource.LLM_GENERATED,
                        steps,
                        currentPlan.constraints());

        LOG.info("Revised plan with " + revisedPlan.stepCount() + " steps");
        return revisedPlan;
    }

    // -----------------------------------------------------------------------
    // Prompt building
    // -----------------------------------------------------------------------

    private String buildPlanningPrompt(PlanRequest request) {
        return PLANNING_PROMPT_TEMPLATE.formatted(
                request.prompt(),
                formatTools(request.availableTools()),
                formatContext(request.context()),
                request.constraints().maxSteps());
    }

    private String buildRevisionPrompt(Plan currentPlan, RevisionContext context) {
        return REVISION_PROMPT_TEMPLATE.formatted(
                context.prompt(),
                formatPlanSteps(currentPlan.steps()),
                context.failedAtStep(),
                context.revisionReason(),
                formatTools(context.availableTools()));
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
            String label =
                    switch (step.action()) {
                        case PlanStepAction.ToolCall tc -> tc.toolName();
                        case PlanStepAction.Synthesize _ -> "synthesize";
                    };
            sb.append(step.index())
                    .append(". ")
                    .append(label)
                    .append(": ")
                    .append(step.description())
                    .append(" [")
                    .append(step.status())
                    .append("]\n");
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Response extraction
    // -----------------------------------------------------------------------

    private List<PlannedStep> extractSteps(AgentResponse response) throws PlanCreationException {
        return switch (response) {
            case AgentResponse.PlanProposal proposal -> {
                LOG.fine("Received PlanProposal response");
                yield proposal.steps();
            }
            case AgentResponse.TextResponse text -> {
                LOG.fine("Received TextResponse, parsing JSON");
                yield responseParser.parse(text.content());
            }
            case AgentResponse.Error error ->
                    throw new PlanCreationException("Planning agent failed: " + error.message());
            case AgentResponse.ToolRequest toolRequest ->
                    throw new PlanCreationException(
                            "Unexpected tool request from planning agent: "
                                    + toolRequest.toolName());
        };
    }
}
