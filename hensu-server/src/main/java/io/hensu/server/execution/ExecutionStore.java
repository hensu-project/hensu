package io.hensu.server.execution;

import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.WorkflowStateReader;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// The durable side of a run: who holds each execution, its main-flow state, and the
/// journal of steps the run cannot reproduce.
///
/// The store is also the only reader of that state: as a {@link WorkflowStateReader} it
/// serves the API's queries from the same rows its claims guard. It offers no write outside
/// a claim, so no caller can bypass the fence.
///
/// ### Claims
/// An execution is `RUNNING` under exactly one claim, `RUNNABLE` (released, waiting for a
/// node), `PAUSED` (waiting for a reviewer) or `DONE`. Starting, resuming and picking up
/// are the only ways to get a claim, and each one increments the epoch. A claim expires
/// unless renewed; expiry is written and judged by the store's clock alone, so no node's
/// wall clock can make a live claim look stale.
///
/// ### Fencing
/// Every method taking a {@link Claim} commits only while that claim is current and the
/// execution running, and is atomic with that check. When the claim no longer stands –
/// or the store cannot be reached to find out – it throws {@link RunPreemptedException}:
/// the run must stop, and whichever run holds the execution next continues it.
///
/// @implNote Implementations are thread-safe; concurrent branches of one run use the
///     same claim.
public interface ExecutionStore extends WorkflowStateReader {

    /// A client's idempotency key for a start, with the fingerprint of the request it came
    /// with.
    ///
    /// @param key the client's key, unique per tenant for as long as its execution is kept,
    ///     not null
    /// @param requestDigest {@link CanonicalDigest} of the start request, not null
    record StartKey(String key, String requestDigest) {
        public StartKey {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(requestDigest, "requestDigest must not be null");
        }
    }

    /// Outcome of {@link #start(String, HensuSnapshot, StartKey)}.
    sealed interface StartOutcome {

        /// The execution was created and this caller holds its first claim.
        ///
        /// @param claim the claim, epoch 1
        record Started(Claim claim) implements StartOutcome {}

        /// An execution already carries the key; nothing was created.
        ///
        /// @param executionId the execution the key belongs to
        /// @param sameRequest whether that execution was started by the same request – a
        ///     retry – rather than a different request reusing the key
        record Existing(String executionId, boolean sameRequest) implements StartOutcome {}
    }

    /// Creates a running execution under its first claim, unless the key already names
    /// one.
    ///
    /// The row exists, durably claimed, before the start request is answered: a node that
    /// dies right after accepting leaves an execution that expires and is picked up, not
    /// one that never happened. With a key the insert and the key check are one
    /// statement, so concurrent retries of one start create one execution between them.
    ///
    /// @param tenantId the tenant, not null
    /// @param initial the execution's state before its first node, not null
    /// @param key the client's idempotency key, or null for a start without one
    /// @return the new claim, or the execution the key already belongs to, never null
    StartOutcome start(String tenantId, HensuSnapshot initial, StartKey key);

    /// Creates a running execution under its first claim, with no idempotency key.
    ///
    /// @param tenantId the tenant, not null
    /// @param initial the execution's state before its first node, not null
    /// @return the claim, epoch 1, never null
    default Claim start(String tenantId, HensuSnapshot initial) {
        return switch (start(tenantId, initial, null)) {
            case StartOutcome.Started started -> started.claim();
            case StartOutcome.Existing existing ->
                    throw new IllegalStateException(
                            "A start without a key matched " + existing.executionId());
        };
    }

    /// Claims a paused execution for resumption.
    ///
    /// @param tenantId the tenant, not null
    /// @param executionId the execution, not null
    /// @return the claim, or empty when the execution is not paused
    Optional<Claim> resume(String tenantId, String executionId);

    /// Claims up to `limit` executions whose claim expired or that were released.
    ///
    /// @param limit the most executions this node takes on now, non-negative
    /// @return the claims taken, never null
    List<Claim> pickUp(int limit);

    /// Extends the claims this node's runs still hold.
    ///
    /// @param claims the claims to renew, not null
    /// @return the subset that renewed; any other has been superseded, never null
    Set<Claim> renew(Collection<Claim> claims);

    /// Writes the main-flow checkpoint that opens a node attempt, and drops the journal
    /// of every earlier attempt, which the checkpoint supersedes.
    ///
    /// @throws RunPreemptedException if the claim no longer stands
    void checkpoint(Claim claim, HensuSnapshot snapshot);

    /// Records that the execution waits for a reviewer and gives up the claim.
    ///
    /// @throws RunPreemptedException if the claim no longer stands
    void pause(Claim claim, HensuSnapshot snapshot);

    /// Records the execution's terminal state, gives up the claim, and cancels the tool
    /// intents no relay has taken yet.
    ///
    /// @throws RunPreemptedException if the claim no longer stands
    void finish(Claim claim, HensuSnapshot snapshot);

    /// Hands a running execution back to the cluster, to be picked up at once.
    ///
    /// @throws RunPreemptedException if the claim no longer stands
    void release(Claim claim);

    /// Returns the model turn journaled at `index` of the attempt at `position`.
    ///
    /// @throws RunPreemptedException if the store cannot be read
    Optional<String> turn(Claim claim, String position, int index);

    /// Journals a model turn, returning what the journal holds afterwards.
    ///
    /// @return the journaled payload – `payload`, or the one already recorded there
    /// @throws RunPreemptedException if the claim no longer stands
    String recordTurn(Claim claim, String position, int index, String payload);

    /// Returns the latest checkpoint a nested flow journaled under `scope`.
    ///
    /// @throws RunPreemptedException if the store cannot be read
    Optional<HensuSnapshot> flowCheckpoint(Claim claim, String scope);

    /// Journals a nested flow's checkpoint, replacing its previous one.
    ///
    /// @throws RunPreemptedException if the claim no longer stands
    void recordFlowCheckpoint(Claim claim, String scope, HensuSnapshot snapshot);

    /// Returns the main-flow step a journal position belongs to: the digits it starts with.
    ///
    /// @param position a node attempt's position or a nested flow's scope, not null
    /// @return the main-flow step, never negative
    static long mainStep(String position) {
        int end = 0;
        while (end < position.length() && Character.isDigit(position.charAt(end))) {
            end++;
        }
        if (end == 0) {
            throw new IllegalArgumentException("Not a journal position: " + position);
        }
        return Long.parseLong(position.substring(0, end));
    }
}
