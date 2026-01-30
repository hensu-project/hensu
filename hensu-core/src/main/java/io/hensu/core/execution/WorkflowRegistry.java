package io.hensu.core.execution;

import io.hensu.core.workflow.Workflow;

/// Registry for loading workflow definitions by identifier.
///
/// Provides a central lookup mechanism for sub-workflow execution. Workflows
/// are loaded on-demand when referenced by {@link io.hensu.core.workflow.node.SubWorkflowNode}.
///
/// @implNote Current implementation returns null (stub). Production implementations
/// should load workflows from file system, database, or other persistent storage.
///
/// @see io.hensu.core.execution.executor.SubWorkflowNodeExecutor for usage
public class WorkflowRegistry {

    /// Loads a workflow definition by its identifier.
    ///
    /// @param workflowId unique identifier of the workflow to load, not null
    /// @return the workflow definition, or null if not found
    public static Workflow load(String workflowId) {
        return null;
    }
}
