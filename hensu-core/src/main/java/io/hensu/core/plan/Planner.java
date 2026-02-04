package io.hensu.core.plan;

import io.hensu.core.tool.ToolDefinition;
import java.util.List;
import java.util.Map;

/// Creates and revises execution plans.
///
/// Planners are responsible for generating sequences of tool invocations
/// to achieve a goal. The core module provides:
/// - {@link StaticPlanner}: Returns predefined plans from DSL
///
/// The server module will provide:
/// - `LlmPlanner`: Generates plans dynamically using an LLM
///
/// ### Contracts
/// - **Precondition**: Request must contain valid goal and tools
/// - **Postcondition**: Returns valid Plan or throws exception
///
/// ### Usage
/// {@snippet :
/// Planner planner = new StaticPlanner(predefinedPlan);
///
/// Plan plan = planner.createPlan(new PlanRequest(
///     "Process the order",
///     availableTools,
///     Map.of("orderId", "123"),
///     PlanConstraints.defaults()
/// ));
/// }
///
/// @see Plan for the output structure
/// @see StaticPlanner for predefined plan support
public interface Planner {

    /// Creates an initial plan for a goal.
    ///
    /// @param request the planning request with goal, tools, and constraints
    /// @return a new plan, never null
    /// @throws PlanCreationException if plan cannot be created
    Plan createPlan(PlanRequest request) throws PlanCreationException;

    /// Revises a plan after step failure or new information.
    ///
    /// Only supported by planners that allow replanning (e.g., LLM planners).
    /// Static planners will throw {@link PlanRevisionException}.
    ///
    /// @param currentPlan the plan to revise, not null
    /// @param context revision context with failure details
    /// @return revised plan, never null
    /// @throws PlanRevisionException if revision is not supported or fails
    Plan revisePlan(Plan currentPlan, RevisionContext context) throws PlanRevisionException;

    /// Request for plan creation.
    ///
    /// @param goal the objective to achieve, not null
    /// @param availableTools tools that can be used in the plan, not null
    /// @param context workflow variables for template resolution, not null
    /// @param constraints limits on plan generation, not null
    record PlanRequest(
            String goal,
            List<ToolDefinition> availableTools,
            Map<String, Object> context,
            PlanConstraints constraints) {
        /// Compact constructor with defaults.
        public PlanRequest {
            goal = goal != null ? goal : "";
            availableTools = availableTools != null ? List.copyOf(availableTools) : List.of();
            context = context != null ? Map.copyOf(context) : Map.of();
            constraints = constraints != null ? constraints : PlanConstraints.defaults();
        }

        /// Creates a simple request with just a goal.
        ///
        /// @param goal the objective to achieve
        /// @return new request, never null
        public static PlanRequest simple(String goal) {
            return new PlanRequest(goal, List.of(), Map.of(), PlanConstraints.defaults());
        }
    }

    /// Context for plan revision after failure.
    ///
    /// @param failedAtStep index of the step that failed
    /// @param failureResult the failed step's result, not null
    /// @param revisionReason explanation for why revision is needed, not null
    record RevisionContext(int failedAtStep, StepResult failureResult, String revisionReason) {
        /// Creates a context from a failed step result.
        ///
        /// @param stepResult the failed step result, not null
        /// @return revision context, never null
        public static RevisionContext fromFailure(StepResult stepResult) {
            return new RevisionContext(
                    stepResult.stepIndex(),
                    stepResult,
                    "Step " + stepResult.stepIndex() + " failed: " + stepResult.error());
        }
    }
}
