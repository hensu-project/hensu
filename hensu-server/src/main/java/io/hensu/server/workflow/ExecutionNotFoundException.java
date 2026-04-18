package io.hensu.server.workflow;

import java.io.Serial;

/// Thrown when an execution cannot be found.
public class ExecutionNotFoundException extends RuntimeException {
    @Serial private static final long serialVersionUID = -4295834705797331331L;

    public ExecutionNotFoundException(String message) {
        super(message);
    }
}
