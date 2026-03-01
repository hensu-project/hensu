package io.hensu.core.execution.executor;

import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.plan.PlanContext;
import io.hensu.core.plan.PlanProcessor;
import java.util.Map;
import java.util.Optional;

/// {@link PlanProcessor} that pauses execution after the plan steps have run,
/// allowing humans to review the plan output before the result is returned.
///
/// This is Gate 2 in the plan lifecycle — Gate 1 ({@link ReviewGateProcessor})
/// runs before execution to let humans review the plan structure; this gate
/// runs after execution to let humans inspect the results.
///
/// Both gates are controlled by the single
/// {@link io.hensu.core.plan.PlanningConfig#review()} flag: when {@code true},
/// both gates activate.
///
/// ### Behavior
/// - If {@code review} is {@code false} — passes through and returns the
///   execution result as the final {@link NodeResult} (terminates the pipeline)
/// - If {@code review} is {@code true} — returns a {@link ResultStatus#PENDING}
///   result with plan ID and result summary in metadata
///
/// ### Contract
/// Requires {@link PlanContext#getExecutionResult()} to be non-null;
/// {@link PlanExecutionProcessor} must run before this processor.
///
/// @implNote Stateless and dependency-free. Thread-safe.
///
/// @see PlanExecutionProcessor for the preceding pipeline stage
/// @see ReviewGateProcessor for Gate 1
public final class PostExecutionReviewGateProcessor implements PlanProcessor {

    @Override
    public Optional<NodeResult> process(PlanContext context) {
        NodeResult executionResult = context.getExecutionResult();

        if (!context.node().getPlanningConfig().review()) {
            // No review — short-circuit with the actual execution result
            return Optional.of(executionResult);
        }

        // Review required — pause with PENDING so the caller can inspect the output
        return Optional.of(
                NodeResult.builder()
                        .status(ResultStatus.PENDING)
                        .output("Plan execution awaiting review")
                        .metadata(
                                Map.of(
                                        "_plan_id",
                                        context.getPlan().id(),
                                        "_plan_result_review_required",
                                        true,
                                        "_plan_result_status",
                                        executionResult.getStatus().name(),
                                        "_plan_result_output",
                                        executionResult.getOutput() != null
                                                ? executionResult.getOutput().toString()
                                                : ""))
                        .build());
    }
}
