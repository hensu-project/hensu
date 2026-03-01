package io.hensu.core.execution.executor;

import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanContext;
import io.hensu.core.plan.PlanCreationException;
import io.hensu.core.plan.PlanProcessor;
import io.hensu.core.plan.Planner;
import io.hensu.core.plan.Planner.PlanRequest;
import io.hensu.core.plan.PlanningConfig;
import io.hensu.core.template.TemplateResolver;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolRegistry;
import io.hensu.core.workflow.node.StandardNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// {@link PlanProcessor} that creates the {@link Plan} and stores it on the
/// {@link PlanContext} for downstream processors.
///
/// Handles both {@link io.hensu.core.plan.PlanningMode#STATIC STATIC}
/// and {@link io.hensu.core.plan.PlanningMode#DYNAMIC DYNAMIC} modes:
///
/// - **STATIC** — reads the pre-defined plan from the node DSL configuration
/// - **DYNAMIC** — invokes {@link Planner} at runtime; fires
///   {@code onPlannerStart} / {@code onPlannerComplete} listener events
///
/// On success the plan is written to {@link PlanContext#setPlan(Plan)} and
/// {@link Optional#empty()} is returned so the pipeline continues.
/// On {@link PlanCreationException} the processor short-circuits with a
/// {@link ResultStatus#FAILURE} result, preserving any
/// {@code _plan_failure_target} metadata set on the node.
///
/// @implNote Stateless beyond injected dependencies. Thread-safe.
///
/// @see SynthesizeEnrichmentProcessor for the next pipeline stage
/// @see io.hensu.core.plan.PlanPipeline for pipeline composition
public final class PlanCreationProcessor implements PlanProcessor {

    private final Planner planner;
    private final ToolRegistry toolRegistry;

    /// Creates a processor backed by the given planner and tool registry.
    ///
    /// @param planner      used for dynamic plan generation, not null
    /// @param toolRegistry provides available tools to the planner, not null
    public PlanCreationProcessor(Planner planner, ToolRegistry toolRegistry) {
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
    }

    @Override
    public Optional<NodeResult> process(PlanContext context) {
        try {
            Plan plan = createPlan(context);
            context.setPlan(plan);
            return Optional.empty();
        } catch (PlanCreationException e) {
            return Optional.of(buildFailureResult(context.node(), e));
        }
    }

    // -----------------------------------------------------------------------
    // Plan creation by mode
    // -----------------------------------------------------------------------

    private Plan createPlan(PlanContext context) throws PlanCreationException {
        StandardNode node = context.node();
        ExecutionContext executionContext = context.executionContext();
        PlanningConfig config = node.getPlanningConfig();

        return switch (config.mode()) {
            case STATIC -> {
                Plan staticPlan = node.getStaticPlan();
                if (staticPlan == null) {
                    throw new PlanCreationException(
                            "Static plan not defined for node: " + node.getId());
                }
                yield staticPlan;
            }

            case DYNAMIC -> {
                List<ToolDefinition> tools = toolRegistry.all();
                String prompt = resolvePrompt(node.getPrompt(), executionContext);

                executionContext.getListener().onPlannerStart(node.getId(), prompt);
                Plan created =
                        planner.createPlan(
                                new PlanRequest(
                                        prompt,
                                        tools,
                                        executionContext.getState().getContext(),
                                        config.constraints()));
                executionContext.getListener().onPlannerComplete(node.getId(), created.steps());
                yield created;
            }

            case DISABLED ->
                    throw new PlanCreationException("Planning disabled for node: " + node.getId());
        };
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private NodeResult buildFailureResult(StandardNode node, PlanCreationException e) {
        String failureTarget = node.getPlanFailureTarget();
        if (failureTarget != null) {
            return NodeResult.builder()
                    .status(ResultStatus.FAILURE)
                    .output("Plan creation failed: " + e.getMessage())
                    .metadata(Map.of("_plan_failure_target", failureTarget))
                    .build();
        }
        return NodeResult.failure("Plan creation failed: " + e.getMessage());
    }

    /// Resolves a prompt template using the execution context's {@link TemplateResolver}.
    ///
    /// Uses {@code ctx.getTemplateResolver()} directly per the Option C convention:
    /// no wrapper utility, same two-line logic as {@code AgenticNodeExecutor.resolvePrompt()}.
    private String resolvePrompt(String prompt, ExecutionContext executionContext) {
        if (prompt == null) {
            return "";
        }
        TemplateResolver resolver = executionContext.getTemplateResolver();
        return resolver != null
                ? resolver.resolve(prompt, executionContext.getState().getContext())
                : prompt;
    }
}
