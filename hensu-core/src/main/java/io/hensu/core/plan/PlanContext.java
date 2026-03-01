package io.hensu.core.plan;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.workflow.node.StandardNode;
import java.util.Objects;

/// Mutable carrier passed through the {@link PlanPipeline}.
///
/// Wraps the immutable {@link StandardNode} and the long-lived
/// {@link ExecutionContext}, and adds the one mutable field that must
/// flow between processors: the {@link Plan} reference.
///
/// ### Why Mutable
/// Unlike {@link io.hensu.core.execution.pipeline.ProcessorContext} (an immutable
/// record), {@code PlanContext} exposes {@link #setPlan(Plan)} so that
/// {@code PlanCreationProcessor} can set the plan once created, and downstream
/// processors (e.g., {@code SynthesizeEnrichmentProcessor}) can read and update
/// the same reference without returning a new carrier object.
///
/// ### Contracts
/// - {@code node} and {@code executionContext} are final and non-null after construction
/// - {@code plan} starts {@code null}; processors must not read it before
///   {@code PlanCreationProcessor} has set it
///
/// @see PlanProcessor for processors that consume this context
/// @see PlanPipeline for pipeline execution
public final class PlanContext {

    private final StandardNode node;
    private final ExecutionContext executionContext;
    private Plan plan;
    private NodeResult executionResult;

    /// Creates a new context with no plan set.
    ///
    /// @param node             the workflow node being executed, not null
    /// @param executionContext  the workflow-scoped execution context, not null
    public PlanContext(StandardNode node, ExecutionContext executionContext) {
        this.node = Objects.requireNonNull(node, "node must not be null");
        this.executionContext =
                Objects.requireNonNull(executionContext, "executionContext must not be null");
    }

    /// Returns the workflow node whose plan is being processed.
    ///
    /// @return the node, never null
    public StandardNode node() {
        return node;
    }

    /// Returns the workflow-scoped execution context.
    ///
    /// @return the execution context, never null
    public ExecutionContext executionContext() {
        return executionContext;
    }

    /// Returns the current plan reference.
    ///
    /// May be {@code null} before {@code PlanCreationProcessor} runs.
    ///
    /// @return the plan, or null if not yet created
    public Plan getPlan() {
        return plan;
    }

    /// Sets the plan reference.
    ///
    /// Called by {@code PlanCreationProcessor} after plan creation and by
    /// {@code SynthesizeEnrichmentProcessor} after step enrichment.
    ///
    /// @param plan the plan to store, not null
    public void setPlan(Plan plan) {
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
    }

    /// Returns the result produced by plan execution.
    ///
    /// Set by {@code PlanExecutionProcessor} after the step loop finishes
    /// (success or failure). {@code null} before execution completes.
    ///
    /// @return the execution result, or null if not yet set
    public NodeResult getExecutionResult() {
        return executionResult;
    }

    /// Sets the execution result after the step loop completes.
    ///
    /// @param executionResult the result to store, not null
    public void setExecutionResult(NodeResult executionResult) {
        this.executionResult =
                Objects.requireNonNull(executionResult, "executionResult must not be null");
    }
}
