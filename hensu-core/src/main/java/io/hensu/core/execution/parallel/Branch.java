package io.hensu.core.execution.parallel;

/// Represents a single execution branch within a parallel node.
///
/// A branch defines one path of concurrent execution, specifying which agent
/// executes what prompt, with optional rubric-based evaluation.
///
/// @param id unique identifier for this branch within the parallel node, not null
/// @param agentId identifier of the agent to execute this branch, not null
/// @param prompt the prompt template to send to the agent, may be null
/// @param rubricId optional rubric identifier for evaluating branch output, may be null
///
/// @see io.hensu.core.workflow.node.ParallelNode for parallel execution
/// @see BranchResult for execution results
public record Branch(String id, String agentId, String prompt, String rubricId) {

    /// Returns the agent identifier for this branch.
    ///
    /// @return the agent ID, not null
    public String getAgentId() {
        return agentId;
    }

    /// Returns the prompt template for this branch.
    ///
    /// @return the prompt, may be null
    public String getPrompt() {
        return prompt;
    }

    /// Returns the unique identifier for this branch.
    ///
    /// @return the branch ID, not null
    public String getId() {
        return id;
    }
}
