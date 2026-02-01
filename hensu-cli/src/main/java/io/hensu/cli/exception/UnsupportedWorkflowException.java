package io.hensu.cli.exception;

import java.io.Serial;

/// Thrown when a workflow cannot be loaded or is not supported.
///
/// Common causes:
/// - Workflow file not found in working directory
/// - No workflow name specified and no default configured
/// - Unsupported workflow file format
///
/// @see io.hensu.cli.commands.WorkflowCommand
public class UnsupportedWorkflowException extends Exception {

    @Serial private static final long serialVersionUID = -8031247384280480051L;

    /// Creates an exception with the specified detail message.
    ///
    /// @param message description of why the workflow is unsupported, not null
    public UnsupportedWorkflowException(String message) {
        super(message);
    }
}
