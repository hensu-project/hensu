package io.hensu.server.workflow;

import java.io.Serial;

/// Thrown when this node must not run an execution.
///
/// Raised when another node holds the execution's lease, or when the tenant's
/// MCP session is held by another node – a tool call is answered over that
/// session, so an execution run here would fail at its first tool call. The
/// REST layer answers 409: the request is valid, but the node that can serve
/// it is not this one, which is why tenant affinity at the load balancer is a
/// deployment requirement. Also raised when a run of the execution is already
/// live on this node, which conflicts in the same way.
///
/// @see io.hensu.server.persistence.ExecutionLeaseManager#tryClaim for the claim that refuses
public class ExecutionNotLocalException extends RuntimeException {
    @Serial private static final long serialVersionUID = 2817394650183746201L;

    /// Creates the exception.
    ///
    /// @param message what refused the execution, naming the other node when known, not null
    public ExecutionNotLocalException(String message) {
        super(message);
    }
}
