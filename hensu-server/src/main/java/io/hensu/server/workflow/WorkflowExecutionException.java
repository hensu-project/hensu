package io.hensu.server.workflow;

import java.io.Serial;

/// Thrown when workflow execution fails at a layer above the core executor.
public class WorkflowExecutionException extends RuntimeException {
    @Serial private static final long serialVersionUID = 2286066982933451717L;

    public WorkflowExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
