package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;

/// Per-iteration context passed through the processor pipeline.
///
/// Wraps the long-lived {@link ExecutionContext} (service locator for the whole
/// workflow run) and adds the per-iteration data that changes each loop:
/// the current node and its execution result.
///
/// ### Design Rationale
/// {@link ExecutionContext} is created once per workflow execution and shared
/// with all {@link io.hensu.core.execution.executor.NodeExecutor} implementations.
/// This record adds the transient per-step fields without duplicating what
/// `ExecutionContext` already carries.
///
/// ### Contracts
/// - **Precondition**: `executionContext` and `currentNode` are always non-null
/// - `result` is null for pre-execution processors, non-null for post-execution
///
/// @param executionContext the workflow-scoped execution context with state and services, not null
/// @param currentNode the node just executed (post) or about to execute (pre), not null
/// @param result the node execution result, null for pre-execution pipeline
///
/// @see ExecutionContext for the underlying service locator
/// @see NodeExecutionProcessor for processors that consume this context
public record ProcessorContext(
        ExecutionContext executionContext, Node currentNode, NodeResult result) {

    /// Convenience accessor for the mutable workflow state.
    ///
    /// @return current workflow state, never null
    public HensuState state() {
        return executionContext.getState();
    }

    /// Convenience accessor for the workflow definition.
    ///
    /// @return workflow being executed, never null
    public Workflow workflow() {
        return executionContext.getWorkflow();
    }
}
