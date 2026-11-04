package io.hensu.server.mcp;

import io.hensu.core.execution.ReplayDivergenceException;
import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.tool.ToolCall;
import io.hensu.server.execution.Claim;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Tool calls as durable intents that a tenant's relay pulls, acknowledges and completes.
///
/// ### Lifecycle
/// A run *submits* an intent under its claim; any node *offers* it to a polling relay
/// under a single-use token; the relay *acknowledges* the token before executing, which
/// starts the call; the relay *completes* it through any node, and the first outcome
/// recorded wins. An offer not acknowledged within the ack deadline is withdrawn and
/// offered again under a new token, so a lost poll response delays a call instead of
/// stranding it. Exactly one acknowledgement can succeed per intent, which is what makes
/// execution at-most-once: an intent the relay acknowledged and never completed settles
/// as `TIMEOUT` at its call deadline – outcome unknown – and is never offered again. An
/// intent no relay acknowledged before its dispatch deadline settles as `UNAVAILABLE`:
/// it did not run.
///
/// @implNote Implementations are thread-safe and signal {@link ClusterSignals} when an
///     intent becomes offerable or settles.
public interface ToolIntents {

    /// An intent as the run that submitted it sees it.
    ///
    /// @param id the intent's identity in the store
    /// @param key the call key, stable across replays, not null
    record Intent(long id, String key) {}

    /// How an intent settled.
    ///
    /// @param status `SUCCESS`, `FAILURE`, `TIMEOUT`, `UNAVAILABLE` or `CANCELLED`
    /// @param outcome the relay's result or error object as JSON, null for deadline outcomes
    record Settlement(String status, String outcome) {}

    /// An intent handed to a polling relay.
    ///
    /// @param token the single-use token the relay acknowledges and completes with
    /// @param key the call's identity within the tenant – execution and call key, see
    ///     {@link io.hensu.core.tool.ToolCall#id} – stable across recovery, which a relay
    ///     may de-duplicate on
    /// @param toolName the tool to execute
    /// @param arguments the arguments, unredacted – the relay needs them
    /// @param deadlineMillis how long the call may take once acknowledged
    record Offer(
            String token,
            String key,
            String toolName,
            Map<String, Object> arguments,
            long deadlineMillis) {}

    /// Outcome of an acknowledgement.
    enum Ack {
        /// The token was the current offer and the call is now started: execute it.
        STARTED,
        /// The token is not a current offer of the caller's tenant: do not execute.
        NOT_OFFERED
    }

    /// Outcome of a completion.
    enum Completion {
        /// The outcome was recorded.
        SETTLED,
        /// The intent had already settled; this outcome was not recorded.
        ALREADY_SETTLED,
        /// No started intent of the caller's tenant carries this token.
        UNKNOWN
    }

    /// Submits a call under the run's claim, or finds the intent a replay of the same
    /// call submitted before.
    ///
    /// @param claim the run's claim, not null
    /// @param call the call, not null
    /// @return the intent, never null
    /// @throws RunPreemptedException if the claim no longer stands or the store is out of reach
    /// @throws ReplayDivergenceException if the key carries a different call than before
    Intent submit(Claim claim, ToolCall call);

    /// Reads an intent's settlement.
    ///
    /// @throws RunPreemptedException if the store is out of reach
    Optional<Settlement> settlement(Intent intent);

    /// Offers up to `max` pending intents of a tenant under fresh tokens.
    List<Offer> offer(String tenantId, int max);

    /// Acknowledges an offer, starting the call.
    Ack ack(String tenantId, String token);

    /// Records the outcome of a started call.
    Completion complete(String tenantId, String token, Settlement outcome);

    /// Applies the ack, call and dispatch deadlines that have passed.
    void expireDeadlines();
}
