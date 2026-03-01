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
    /// @param prompt the resolved node prompt driving this plan, not null
    /// @param availableTools tools that can be used in the plan, not null
    /// @param context workflow variables for template resolution, not null
    /// @param constraints limits on plan generation, not null
    record PlanRequest(
            String prompt,
            List<ToolDefinition> availableTools,
            Map<String, Object> context,
            PlanConstraints constraints) {
        /// Compact constructor with defaults.
        public PlanRequest {
            prompt = prompt != null ? prompt : "";
            availableTools = availableTools != null ? List.copyOf(availableTools) : List.of();
            context = context != null ? Map.copyOf(context) : Map.of();
            constraints = constraints != null ? constraints : PlanConstraints.defaults();
        }

        /// Creates a simple request with just a prompt.
        ///
        /// @param prompt the resolved node prompt
        /// @return new request, never null
        public static PlanRequest simple(String prompt) {
            return new PlanRequest(prompt, List.of(), Map.of(), PlanConstraints.defaults());
        }
    }

    /// Context for plan revision after failure.
    ///
    /// Carries the original node prompt and available tools so the planner can
    /// produce a meaningful revised plan without re-deriving them from the node.
    ///
    /// @param failedAtStep index of the step that failed
    /// @param failureResult the failed step's result, not null
    /// @param revisionReason explanation for why revision is needed, not null
    /// @param prompt the resolved node prompt that drove the original plan, not null
    /// @param availableTools tools available for the revised plan, not null
    record RevisionContext(
            int failedAtStep,
            StepResult failureResult,
            String revisionReason,
            String prompt,
            List<ToolDefinition> availableTools) {

        /// Compact constructor with defaults.
        public RevisionContext {
            prompt = prompt != null ? prompt : "";
            availableTools = availableTools != null ? List.copyOf(availableTools) : List.of();
        }

        /// Creates a context from a failed step result.
        ///
        /// @param stepResult the failed step result, not null
        /// @param prompt the resolved node prompt that drove the original plan, not null
        /// @param availableTools tools available for replanning, not null
        /// @return revision context, never null
        public static RevisionContext fromFailure(
                StepResult stepResult, String prompt, List<ToolDefinition> availableTools) {
            return new RevisionContext(
                    stepResult.stepIndex(),
                    stepResult,
                    "Step " + stepResult.stepIndex() + " failed: " + stepResult.error(),
                    prompt,
                    availableTools);
        }
    }
}
