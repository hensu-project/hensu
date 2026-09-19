package io.hensu.server.persistence;

import java.util.List;

/// Durable store for the tool invocations an execution made.
///
/// The listener hooks already carry a decision, an exit status and a timestamp, but only
/// for the life of the process and — for the logging sink — only when a configuration
/// flag happens to be on. A record that a default deployment does not keep is not a
/// record. This interface is where it is kept, and it is composed unconditionally rather
/// than behind the verbosity switch.
///
/// ### Contracts
/// - **Postcondition**: a returned call has either stored the entry or logged why not
/// - **Invariant**: a write failure never propagates — an audit sink that can abort a run
///   is worse than a gap in the trail, and the run is the thing with real effects
///
/// @see ToolAuditEntry for what a row holds
/// @see io.hensu.server.execution.ToolAuditListener for what writes them
public interface ToolAuditRepository {

    /// Stores one settled invocation.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @param entry the invocation to record, not null
    /// @apiNote **Side effects**: writes durably. Never throws.
    void record(String tenantId, ToolAuditEntry entry);

    /// Reads back an execution's invocations, oldest first.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @param executionId the execution to read, not null
    /// @return the entries in the order they settled, never null
    List<ToolAuditEntry> findByExecution(String tenantId, String executionId);
}
