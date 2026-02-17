package io.hensu.cli.exception;

import java.io.Serial;

public class UnsupportedWorkflowException extends Exception {
    @Serial private static final long serialVersionUID = -8031247384280480051L;

    public UnsupportedWorkflowException(String message) {
        super(message);
    }
}
