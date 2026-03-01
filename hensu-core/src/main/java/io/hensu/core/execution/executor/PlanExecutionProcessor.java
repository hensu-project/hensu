package io.hensu.core.execution.executor;

import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanConstraints;
import io.hensu.core.plan.PlanContext;
import io.hensu.core.plan.PlanEvent;
import io.hensu.core.plan.PlanExecutor;
import io.hensu.core.plan.PlanObserver;
import io.hensu.core.plan.PlanProcessor;
import io.hensu.core.plan.PlanResult;
import io.hensu.core.plan.PlanRevisionException;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.plan.Planner;
import io.hensu.core.plan.Planner.RevisionContext;
import io.hensu.core.plan.StepResult;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolRegistry;
import io.hensu.core.workflow.node.StandardNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/// {@link PlanProcessor} that executes a plan's steps and handles replanning
/// on failure, storing the final {@link NodeResult} on the {@link PlanContext}.
///
/// This processor owns the execution loop:
/// 1. Run the plan via {@link PlanExecutor}
/// 2. On success — store the result and notify observers
/// 3. On failure with replanning allowed — revise via
/// {@link io.hensu.core.plan.LlmPlanner}, re-enrich
///    synthesize steps, notify observers, and retry
/// 4. On max replans exceeded or revision failure — store a failure result
///
/// Always returns {@link Optional#empty()} so that the
/// {@link PostExecutionReviewGateProcessor} downstream can inspect the result.
/// The gate decides whether to short-circuit with PENDING or pass through.
///
/// @implNote Stateless beyond injected dependencies. Thread-safe.
///
/// @see SynthesizeEnrichmentProcessor for the preceding pipeline stage
/// @see PostExecutionReviewGateProcessor for the following pipeline stage
public final class PlanExecutionProcessor implements PlanProcessor {

    private static final Logger LOG = Logger.getLogger(PlanExecutionProcessor.class.getName());

    private final Planner planner;
    private final PlanExecutor planExecutor;
    private final ToolRegistry toolRegistry;
    private final List<PlanObserver> observers;

    /// Creates a processor with required execution dependencies.
    ///
    /// @param planner       planner for revision on failure, not null
    /// @param planExecutor  step-by-step executor, not null
    /// @param toolRegistry  provides available tools for revision, not null
    /// @param observers     plan event observers, may be empty but not null
    public PlanExecutionProcessor(
            Planner planner,
            PlanExecutor planExecutor,
            ToolRegistry toolRegistry,
            List<PlanObserver> observers) {
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
        this.planExecutor = Objects.requireNonNull(planExecutor, "planExecutor must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.observers = observers != null ? List.copyOf(observers) : List.of();
    }

    @Override
    public Optional<NodeResult> process(PlanContext context) {
        StandardNode node = context.node();
        ExecutionContext executionContext = context.executionContext();
        Plan originalPlan = context.getPlan();
        PlanConstraints constraints = originalPlan.constraints();

        int replans = 0;
        Plan currentPlan = originalPlan;

        while (replans <= constraints.maxReplans()) {
            PlanResult result =
                    planExecutor.execute(currentPlan, executionContext.getState().getContext());

            if (result.isSuccess()) {
                NodeResult nodeResult = NodeResult.success(result.output(), Map.of());
                context.setExecutionResult(nodeResult);
                notifyObservers(
                        PlanEvent.PlanCompleted.success(
                                originalPlan.id(),
                                result.output() != null
                                        ? result.output().toString()
                                        : "Plan completed"));
                return Optional.empty();
            }

            if (!constraints.allowReplan()) {
                LOG.warning("Plan " + originalPlan.id() + " failed and replanning not allowed");
                notifyObservers(PlanEvent.PlanCompleted.failure(originalPlan.id(), result.error()));
                context.setExecutionResult(buildFailureResult(node, result));
                return Optional.empty();
            }

            try {
                LOG.info(
                        "Revising plan "
                                + currentPlan.id()
                                + " after failure at step "
                                + result.failedAtStep());

                List<StepResult> stepResults = result.stepResults();
                StepResult failedStep =
                        result.failedAtStep() < stepResults.size()
                                ? stepResults.get(result.failedAtStep())
                                : StepResult.failure(
                                        result.failedAtStep(),
                                        "unknown",
                                        result.error(),
                                        Duration.ZERO);
                String prompt = resolvePrompt(node.getPrompt(), executionContext);
                List<ToolDefinition> tools = toolRegistry.all();

                executionContext.getListener().onPlannerStart(node.getId(), prompt);
                Plan revisedPlan =
                        planner.revisePlan(
                                currentPlan,
                                RevisionContext.fromFailure(failedStep, prompt, tools));
                executionContext.getListener().onPlannerComplete(node.getId(), revisedPlan.steps());

                revisedPlan = enrichSynthesizeSteps(revisedPlan, node.getAgentId());

                notifyObservers(
                        PlanEvent.PlanRevised.now(
                                originalPlan.id(),
                                "Replan after step " + result.failedAtStep() + " failure",
                                currentPlan.steps(),
                                revisedPlan.steps()));

                currentPlan = revisedPlan;
                replans++;
            } catch (PlanRevisionException e) {
                LOG.warning(
                        "Plan revision failed for plan "
                                + originalPlan.id()
                                + ": "
                                + e.getMessage());
                notifyObservers(PlanEvent.PlanCompleted.failure(originalPlan.id(), e.getMessage()));
                context.setExecutionResult(buildFailureResult(node, result));
                return Optional.empty();
            }
        }

        LOG.warning(
                "Max replans ("
                        + constraints.maxReplans()
                        + ") exceeded for plan "
                        + originalPlan.id());
        notifyObservers(PlanEvent.PlanCompleted.failure(originalPlan.id(), "Max replans exceeded"));
        context.setExecutionResult(NodeResult.failure("Max replans exceeded"));
        return Optional.empty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private NodeResult buildFailureResult(StandardNode node, PlanResult result) {
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

    /// Re-enriches synthesize steps on a revised plan.
    ///
    /// Mirrors {@link SynthesizeEnrichmentProcessor}'s logic for use inside
    /// the revision loop, where creating a full {@link PlanContext} for a
    /// single enrichment call would be unnecessary overhead.
    private Plan enrichSynthesizeSteps(Plan plan, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return plan;
        }
        boolean hasSynthesize = plan.steps().stream().anyMatch(PlannedStep::isSynthesize);
        if (!hasSynthesize) {
            return plan;
        }
        List<PlannedStep> enriched = new ArrayList<>(plan.steps().size());
        for (PlannedStep step : plan.steps()) {
            enriched.add(step.isSynthesize() ? step.withAgentId(agentId) : step);
        }
        return plan.withSteps(enriched);
    }

    private String resolvePrompt(String prompt, ExecutionContext executionContext) {
        if (prompt == null) {
            return "";
        }
        var resolver = executionContext.getTemplateResolver();
        return resolver != null
                ? resolver.resolve(prompt, executionContext.getState().getContext())
                : prompt;
    }

    private void notifyObservers(PlanEvent event) {
        for (PlanObserver observer : observers) {
            try {
                observer.onEvent(event);
            } catch (Exception e) {
                LOG.warning(
                        "Observer failed to process event "
                                + event.getClass().getSimpleName()
                                + ": "
                                + e.getMessage());
            }
        }
    }
}
