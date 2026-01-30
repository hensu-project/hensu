package io.hensu.core.workflow.node;

import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;

/// Base class for all workflow node types.
///
/// Nodes are the building blocks of Hensu workflows. Each node represents
/// a discrete step in the workflow graph with a unique identifier, execution
/// logic, and transition rules to determine the next node.
///
/// ### Node Types
/// - {@link StandardNode} - Agent execution with prompt
/// - {@link EndNode} - Workflow end state (success/failure)
/// - {@link ParallelNode} - Concurrent branch execution
/// - {@link LoopNode} - Iterative execution
/// - {@link SubWorkflowNode} - Nested workflow invocation
/// - {@link ForkNode} / {@link JoinNode} - Fork-join parallelism
/// - {@link GenericNode} - Custom execution logic
///
/// @implNote Subclasses must be immutable after construction. All fields
/// should be set via constructors or builders and not modified afterward.
///
/// @see NodeType for the enumeration of node types
/// @see TransitionRule for controlling workflow flow
public abstract class Node {

    protected final String id;

    /// Creates a node with the specified identifier.
    ///
    /// @param id unique node identifier within the workflow, not null
    protected Node(String id) {
        this.id = id;
    }

    /// Returns the unique node identifier.
    ///
    /// @return node ID used for graph traversal and logging, never null
    public String getId() {
        return id;
    }

    /// Returns the rubric ID for quality evaluation, if configured.
    ///
    /// @return rubric identifier, or null if no rubric evaluation
    public abstract String getRubricId();

    /// Returns the node type for executor dispatch.
    ///
    /// @return the node type enum value, never null
    public abstract NodeType getNodeType();

    /// Returns the transition rules for determining the next node.
    ///
    /// Transition rules are evaluated in order after node execution.
    /// The first rule that returns a non-null target determines the next node.
    ///
    /// @return unmodifiable list of transition rules, never null (may be empty)
    public List<TransitionRule> getTransitionRules() {
        return List.of();
    }
}
