package io.hensu.core.workflow.node;

public enum NodeType {
    STANDARD,
    LOOP,
    PARALLEL,
    SUB_WORKFLOW,
    END,
    GENERIC,
    FORK,
    JOIN,
    ACTION
}
