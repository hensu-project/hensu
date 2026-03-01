package io.hensu.core.execution.executor;

import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanContext;
import io.hensu.core.plan.PlanProcessor;
import io.hensu.core.plan.PlannedStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// {@link PlanProcessor} that fills in the {@code agentId} on all
/// {@link io.hensu.core.plan.PlanStepAction.Synthesize} steps.
///
/// After {@code PlanCreationProcessor} sets the plan, this processor enriches
/// any synthesize steps with the node's configured agent identifier so that
/// {@link io.hensu.core.plan.SynthesizeStepHandler} can resolve the
/// correct agent without the planner needing to know the agent's identity.
///
/// If the node has no {@code agentId} or the plan contains no synthesize steps,
/// the plan is left unchanged and the processor passes through.
///
/// Always returns {@link Optional#empty()} — never short-circuits.
///
/// @implNote Stateless and dependency-free. Thread-safe.
///
/// @see PlanCreationProcessor for the preceding pipeline stage
/// @see ReviewGateProcessor for the following pipeline stage
public final class SynthesizeEnrichmentProcessor implements PlanProcessor {

    @Override
    public Optional<NodeResult> process(PlanContext context) {
        String agentId = context.node().getAgentId();
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }

        Plan plan = context.getPlan();
        boolean hasSynthesize = plan.steps().stream().anyMatch(PlannedStep::isSynthesize);
        if (!hasSynthesize) {
            return Optional.empty();
        }

        List<PlannedStep> enriched = new ArrayList<>(plan.steps().size());
        for (PlannedStep step : plan.steps()) {
            enriched.add(step.isSynthesize() ? step.withAgentId(agentId) : step);
        }
        context.setPlan(plan.withSteps(enriched));
        return Optional.empty();
    }
}
