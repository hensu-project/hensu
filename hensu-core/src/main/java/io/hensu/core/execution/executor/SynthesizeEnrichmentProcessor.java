package io.hensu.core.execution.executor;

import io.hensu.core.execution.enricher.EngineVariablePromptEnricher;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanContext;
import io.hensu.core.plan.PlanProcessor;
import io.hensu.core.plan.PlanStepAction;
import io.hensu.core.plan.PlannedStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// {@link PlanProcessor} that enriches all {@link io.hensu.core.plan.PlanStepAction.Synthesize}
/// steps before plan execution begins.
///
/// Two enrichments are applied to every synthesize step:
///
/// - **Agent ID** — the node's configured agent identifier is injected so that
///   {@link io.hensu.core.plan.SynthesizeStepHandler} can resolve the correct agent
///   without the planner needing to know the agent's identity.
///
/// - **Engine-variable prompt enrichment** — the synthesis prompt is passed through
///   {@link EngineVariablePromptEnricher#DEFAULT}, which appends the same JSON output
///   schema requirements (writes fields, rubric criteria, score/approval variables, etc.)
///   that {@link StandardNodeExecutor} injects for non-planning nodes. Without this,
///   the synthesis LLM produces free-text that {@code OutputExtractionPostProcessor}
///   cannot parse.
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
            if (step.isSynthesize()) {
                String basePrompt = ((PlanStepAction.Synthesize) step.action()).prompt();
                String enrichedPrompt =
                        EngineVariablePromptEnricher.DEFAULT.enrich(
                                basePrompt, context.node(), context.executionContext());
                enriched.add(step.withAgentId(agentId).withSynthesizePrompt(enrichedPrompt));
            } else {
                enriched.add(step);
            }
        }
        context.setPlan(plan.withSteps(enriched));
        return Optional.empty();
    }
}
