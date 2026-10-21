package io.hensu.server.persistence;

import java.io.Serial;

/// Thrown when a snapshot write is refused because this node no longer holds the
/// execution's lease.
///
/// {@link JdbcWorkflowStateRepository#save} only overwrites a row this node
/// owns. A node that stalled past the stale threshold – a long GC pause, a
/// frozen VM – wakes up to find another node has claimed and resumed its
/// execution; its next write is refused with this exception instead of
/// silently replacing the newer state. The run on this node must stop: the
/// execution continues on the node that holds the lease.
///
/// @see ExecutionLeaseManager for how a lease moves between nodes
public class LeaseLostException extends PersistenceException {

    @Serial private static final long serialVersionUID = 4419873602157736012L;

    /// Creates the exception for an execution whose lease is held elsewhere.
    ///
    /// @param executionId the execution whose write was refused, not null
    public LeaseLostException(String executionId) {
        super(
                "Execution "
                        + executionId
                        + " is leased to another node; this node's write was refused",
                null);
    }

    /// Reports whether a lost lease is anywhere in a failure's cause chain.
    ///
    /// The refusal surfaces from inside the executor and may arrive wrapped, so
    /// callers deciding whether to record a failure inspect the whole chain.
    ///
    /// @param failure the failure to inspect, may be null
    /// @return true if `failure` or one of its causes is a `LeaseLostException`
    public static boolean isCause(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof LeaseLostException) {
                return true;
            }
            if (t.getCause() == t) {
                return false;
            }
        }
        return false;
    }
}
