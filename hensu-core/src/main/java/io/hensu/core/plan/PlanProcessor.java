package io.hensu.core.plan;

import io.hensu.core.execution.executor.NodeResult;
import java.util.Optional;

/// Single-responsibility processor for one phase of the plan lifecycle.
///
/// Processors are composed into a {@link PlanPipeline} and executed in order
/// before {@link PlanExecutor} runs the plan's steps. Each processor inspects
/// and optionally mutates the mutable {@link PlanContext}, then returns an
/// {@link Optional} to control pipeline flow.
///
/// ### Return Convention
/// - {@code Optional.empty()} — continue to the next processor
/// - {@code Optional.of(result)} — short-circuit; return this result immediately
///   (e.g., {@code PENDING} for review gate, {@code FAILURE} for creation errors)
///
/// ### Contracts
/// - **Precondition**: {@code context} is fully populated for the current phase
/// - **Postcondition**: Returns a non-null {@code Optional}
/// - **Invariant**: Processors must not call other processors directly
///
/// @implNote Implementations should be stateless. All mutable plan state lives in
/// {@link PlanContext}. Dependencies are injected via constructor and reused.
///
/// @see PlanPipeline for composition and short-circuit logic
/// @see PlanContext for the mutable carrier
public interface PlanProcessor {

    /// Processes one phase of the plan lifecycle.
    ///
    /// @param context mutable plan context carrying the node, execution context, and
    ///                current plan reference (may be null before {@code PlanCreationProcessor}
    ///                runs), not null
    /// @return empty to continue, or a terminal result to short-circuit the pipeline
    Optional<NodeResult> process(PlanContext context);
}
