package io.hensu.core.execution.executor;

import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanContext;
import io.hensu.core.plan.PlanProcessor;
import java.util.Map;
import java.util.Optional;

/// {@link PlanProcessor} that pauses execution when the node requires human
/// plan review before execution proceeds.
///
/// If {@link io.hensu.core.plan.PlanningConfig#review()} is
/// {@code true}, this processor short-circuits the pipeline with a
/// {@link ResultStatus#PENDING} result, embedding the plan ID, a review
/// flag, and the step count in the result metadata so the API layer can
/// surface them to the caller.
///
/// If review is not required, the processor passes through with
/// {@link Optional#empty()}.
///
/// @implNote Stateless and dependency-free. Thread-safe.
///
/// @see SynthesizeEnrichmentProcessor for the preceding pipeline stage
/// @see io.hensu.core.plan.PlanPipeline for pipeline composition
public final class ReviewGateProcessor implements PlanProcessor {

    @Override
    public Optional<NodeResult> process(PlanContext context) {
        if (!context.node().getPlanningConfig().review()) {
            return Optional.empty();
        }

        Plan plan = context.getPlan();
        return Optional.of(
                NodeResult.builder()
                        .status(ResultStatus.PENDING)
                        .output("Plan awaiting review")
                        .metadata(
                                Map.of(
                                        "_plan_id", plan.id(),
                                        "_plan_review_required", true,
                                        "_plan_steps", plan.steps().size()))
                        .build());
    }
}
