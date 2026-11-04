package io.hensu.server.workflow;

import java.io.Serial;

/// A resume was asked of an execution that is not waiting for one.
///
/// The answer is the same on every node – it describes the execution, not where the
/// request landed – so it maps to `409 Conflict`.
public class ExecutionNotPausedException extends RuntimeException {

    @Serial private static final long serialVersionUID = -5257192229731595573L;

    /// @param message which execution, and what state it is in, not null
    public ExecutionNotPausedException(String message) {
        super(message);
    }
}
