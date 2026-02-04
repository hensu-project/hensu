package io.hensu.server.executor;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeExecutor;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanConstraints;
import io.hensu.core.plan.PlanCreationException;
import io.hensu.core.plan.PlanEvent;
import io.hensu.core.plan.PlanExecutor;
import io.hensu.core.plan.PlanObserver;
import io.hensu.core.plan.PlanResult;
import io.hensu.core.plan.PlanRevisionException;
import io.hensu.core.plan.Planner.PlanRequest;
import io.hensu.core.plan.Planner.RevisionContext;
import io.hensu.core.plan.PlanningConfig;
import io.hensu.core.plan.StepResult;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolRegistry;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.server.planner.LlmPlanner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jboss.logging.Logger;

/// Executor for StandardNodes with planning support.
///
/// Extends basic agent execution with planning capabilities:
/// - **Static plans**: Uses predefined steps from DSL
/// - **Dynamic plans**: Generates plans via LLM at runtime
/// - **Plan revision**: Retries with revised plans on failure
///
/// ### Execution Flow
/// 1. Check if planning is enabled for the node
/// 2. If disabled, execute simple agent call
/// 3. If enabled, get or create plan based on mode
/// 4. Optionally pause for human review
/// 5. Execute plan steps via PlanExecutor
/// 6. On failure, revise and retry if allowed
///
/// ### Thread Safety
/// @implNote Thread-safe. All state is passed via context.
///
/// @see StandardNode#hasPlanningEnabled() for planning detection
/// @see PlanExecutor for plan step execution
/// @see LlmPlanner for dynamic plan generation
@ApplicationScoped
public class AgenticNodeExecutor implements NodeExecutor<StandardNode> {

    private static final Logger LOG = Logger.getLogger(AgenticNodeExecutor.class);

    private final LlmPlanner llmPlanner;
    private final PlanExecutor planExecutor;
    private final ToolRegistry toolRegistry;
    private final List<PlanObserver> observers;

    /// Creates the executor with required dependencies.
    ///
    /// @param llmPlanner planner for dynamic plan generation, not null
    /// @param planExecutor executor for plan steps, not null
    /// @param toolRegistry registry of available tools, not null
    /// @param observers plan event observers, may be empty
    public AgenticNodeExecutor(
            LlmPlanner llmPlanner,
            PlanExecutor planExecutor,
            ToolRegistry toolRegistry,
            Instance<PlanObserver> observers) {
        this.llmPlanner = Objects.requireNonNull(llmPlanner, "llmPlanner must not be null");
        this.planExecutor = Objects.requireNonNull(planExecutor, "planExecutor must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.observers = observers.stream().toList();

        // Register observers with plan executor
        this.observers.forEach(planExecutor::addObserver);
    }

    @Override
    public Class<StandardNode> getNodeType() {
        return StandardNode.class;
    }

    @Override
    public NodeResult execute(StandardNode node, ExecutionContext context) {
        if (!node.hasPlanningEnabled()) {
            LOG.debugv("Planning disabled for node {0}, executing simple agent call", node.getId());
            return executeSimple(node, context);
        }

        Plan plan;
        try {
            plan = getOrCreatePlan(node, context);
        } catch (PlanCreationException e) {
            LOG.errorv(e, "Failed to create plan for node {0}", node.getId());
            return handlePlanCreationFailure(node, e);
        }

        PlanningConfig config = node.getPlanningConfig();

        // Check if plan needs human review before execution
        if (config.reviewBeforeExecute()) {
            LOG.infov("Plan {0} paused for review", plan.id());
            return pauseForPlanReview(plan, context);
        }

        return executePlan(plan, node, context);
    }

    /// Executes a simple agent call without planning.
    private NodeResult executeSimple(StandardNode node, ExecutionContext context) {
        AgentRegistry agentRegistry = context.getAgentRegistry();
        if (agentRegistry == null) {
            return NodeResult.failure("No agent registry configured");
        }

        String agentId = node.getAgentId();
        Agent agent = agentRegistry.getAgent(agentId).orElse(null);
        if (agent == null) {
            return NodeResult.failure("Agent not found: " + agentId);
        }

        String prompt = resolvePrompt(node.getPrompt(), context);
        AgentResponse response = agent.execute(prompt, context.getState().getContext());

        return switch (response) {
            case AgentResponse.TextResponse t -> NodeResult.success(t.content(), t.metadata());
            case AgentResponse.Error e -> NodeResult.failure(e.message());
            case AgentResponse.ToolRequest tr ->
                    NodeResult.failure("Agent requested tool without planning: " + tr.toolName());
            case AgentResponse.PlanProposal pp ->
                    NodeResult.failure(
                            "Agent proposed plan without planning mode: "
                                    + pp.steps().size()
                                    + " steps");
        };
    }

    /// Gets or creates a plan based on the node's planning mode.
    private Plan getOrCreatePlan(StandardNode node, ExecutionContext context)
            throws PlanCreationException {
        PlanningConfig config = node.getPlanningConfig();

        return switch (config.mode()) {
            case STATIC -> {
                Plan staticPlan = node.getStaticPlan();
                if (staticPlan == null) {
                    throw new PlanCreationException(
                            "Static plan not defined for node: " + node.getId());
                }
                yield resolvePlaceholders(staticPlan, context);
            }

            case DYNAMIC -> {
                List<ToolDefinition> tools = getAvailableTools(node, context);
                String goal = resolvePrompt(node.getPrompt(), context);

                yield llmPlanner.createPlan(
                        new PlanRequest(
                                goal,
                                tools,
                                context.getState().getContext(),
                                config.constraints()));
            }

            case DISABLED ->
                    throw new PlanCreationException("Planning disabled for node: " + node.getId());
        };
    }

    /// Executes a plan with retry/revision support.
    private NodeResult executePlan(Plan plan, StandardNode node, ExecutionContext context) {
        notifyObservers(PlanEvent.PlanCreated.now(plan));

        int replans = 0;
        Plan currentPlan = plan;
        PlanConstraints constraints = plan.constraints();

        while (replans <= constraints.maxReplans()) {
            PlanResult result = planExecutor.execute(currentPlan, context.getState().getContext());

            if (result.isSuccess()) {
                notifyObservers(
                        PlanEvent.PlanCompleted.success(
                                plan.id(),
                                result.output() != null
                                        ? result.output().toString()
                                        : "Plan completed"));
                return NodeResult.success(result.output(), Map.of());
            }

            // Plan failed
            if (!constraints.allowReplan()) {
                LOG.warnv("Plan {0} failed and replanning not allowed", plan.id());
                return handlePlanFailure(node, result);
            }

            // Try to revise plan
            try {
                LOG.infov(
                        "Revising plan {0} after failure at step {1}",
                        currentPlan.id(), result.failedAtStep());

                StepResult failedStep = result.stepResults().get(result.failedAtStep());
                Plan revisedPlan =
                        llmPlanner.revisePlan(currentPlan, RevisionContext.fromFailure(failedStep));

                notifyObservers(
                        PlanEvent.PlanRevised.now(
                                plan.id(),
                                "Replan after step " + result.failedAtStep() + " failure",
                                currentPlan.steps(),
                                revisedPlan.steps()));

                currentPlan = revisedPlan;
                replans++;
            } catch (PlanRevisionException e) {
                LOG.warnv(e, "Plan revision failed for plan {0}", plan.id());
                return handlePlanFailure(node, result);
            }
        }

        LOG.warnv("Max replans ({0}) exceeded for plan {1}", constraints.maxReplans(), plan.id());
        notifyObservers(PlanEvent.PlanCompleted.failure(plan.id(), "Max replans exceeded"));
        return NodeResult.failure("Max replans exceeded");
    }

    /// Handles plan creation failure.
    private NodeResult handlePlanCreationFailure(StandardNode node, PlanCreationException e) {
        String failureTarget = node.getPlanFailureTarget();
        if (failureTarget != null) {
            // Return with metadata indicating the failure target
            return NodeResult.builder()
                    .status(io.hensu.core.execution.result.ResultStatus.FAILURE)
                    .output("Plan creation failed: " + e.getMessage())
                    .metadata(Map.of("_plan_failure_target", failureTarget))
                    .build();
        }
        return NodeResult.failure("Plan creation failed: " + e.getMessage());
    }

    /// Handles plan execution failure.
    private NodeResult handlePlanFailure(StandardNode node, PlanResult result) {
        String failureTarget = node.getPlanFailureTarget();
        if (failureTarget != null) {
            return NodeResult.builder()
                    .status(io.hensu.core.execution.result.ResultStatus.FAILURE)
                    .output("Plan failed: " + result.error())
                    .metadata(Map.of("_plan_failure_target", failureTarget))
                    .build();
        }
        return NodeResult.failure("Plan failed: " + result.error());
    }

    /// Pauses execution for plan review.
    private NodeResult pauseForPlanReview(Plan plan, ExecutionContext context) {
        // Return a special result that signals the workflow should pause
        return NodeResult.builder()
                .status(io.hensu.core.execution.result.ResultStatus.PENDING)
                .output("Plan awaiting review")
                .metadata(
                        Map.of(
                                "_plan_id", plan.id(),
                                "_plan_review_required", true,
                                "_plan_steps", plan.steps().size()))
                .build();
    }

    /// Gets available tools for plan generation.
    private List<ToolDefinition> getAvailableTools(StandardNode node, ExecutionContext context) {
        // Use all tools from registry
        // Future: Could filter based on node configuration
        return toolRegistry.all();
    }

    /// Resolves placeholders in plan step arguments.
    private Plan resolvePlaceholders(Plan plan, ExecutionContext context) {
        // For now, return plan as-is
        // Template resolution happens in PlanExecutor
        return plan;
    }

    /// Resolves template variables in prompt.
    private String resolvePrompt(String prompt, ExecutionContext context) {
        if (prompt == null) {
            return "";
        }
        if (context.getTemplateResolver() != null) {
            return context.getTemplateResolver().resolve(prompt, context.getState().getContext());
        }
        return prompt;
    }

    /// Notifies all observers of a plan event.
    private void notifyObservers(PlanEvent event) {
        for (PlanObserver observer : observers) {
            try {
                observer.onEvent(event);
            } catch (Exception e) {
                LOG.warnv(
                        e,
                        "Observer failed to process event: {0}",
                        event.getClass().getSimpleName());
            }
        }
    }
}
