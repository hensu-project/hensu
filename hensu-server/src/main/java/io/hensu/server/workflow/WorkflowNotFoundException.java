package io.hensu.server.workflow;

import java.io.Serial;

/// Thrown when a workflow definition cannot be found.
public class WorkflowNotFoundException extends RuntimeException {
    @Serial private static final long serialVersionUID = 6275906519265300703L;

    public WorkflowNotFoundException(String message) {
        super(message);
    }
}
