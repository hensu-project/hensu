package io.hensu.server.persistence;

import io.hensu.core.execution.ReplayDivergenceException;
import io.hensu.core.tool.ToolCall;
import io.hensu.server.execution.CanonicalDigest;
import io.hensu.server.execution.Claim;
import io.hensu.server.mcp.ClusterSignals;
import io.hensu.server.mcp.ToolIntents;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/// Single-process {@link ToolIntents} for deployments without a database.
///
/// The same lifecycle and deadlines as the PostgreSQL implementation, under one lock;
/// claims are checked against the {@link InMemoryExecutionStore} through `holdsClaim`.
///
/// @implNote Thread-safe: every operation holds the queue's lock.
public final class InMemoryToolIntents implements ToolIntents {

    /// Answers whether a claim is the execution's current one.
    @FunctionalInterface
    public interface ClaimCheck {
        boolean holds(Claim claim);
    }

    private static final class Row {
        final long id;
        final String tenantId;
        final String executionId;
        /// The epoch of the claim the intent was made under, as `created_epoch` in the table.
        final long epoch;
        final String key;
        final String toolName;
        final String digest;
        final Instant dispatchDeadline;
        Map<String, Object> arguments;
        String state = "PENDING";
        String token;
        Instant offerExpires;
        Instant deadline;
        Settlement settlement;

        Row(long id, Claim claim, ToolCall call, Instant dispatchDeadline) {
            this.id = id;
            this.tenantId = claim.tenantId();
            this.executionId = claim.executionId();
            this.epoch = claim.epoch();
            this.key = call.key();
            this.toolName = call.toolName();
            this.digest = CanonicalDigest.of(call.arguments());
            this.arguments = call.arguments();
            this.dispatchDeadline = dispatchDeadline;
        }
    }

    private final ClaimCheck claims;
    private final ClusterSignals signals;
    private final Duration ackDeadline;
    private final Duration callDeadline;
    private final Duration dispatchDeadline;
    private final Map<Long, Row> rows = new LinkedHashMap<>();
    private final Map<String, Row> byKey = new HashMap<>();
    private long nextId = 1;

    public InMemoryToolIntents(
            ClaimCheck claims,
            ClusterSignals signals,
            Duration ackDeadline,
            Duration callDeadline,
            Duration dispatchDeadline) {
        this.claims = Objects.requireNonNull(claims, "claims must not be null");
        this.signals = Objects.requireNonNull(signals, "signals must not be null");
        this.ackDeadline = ackDeadline;
        this.callDeadline = callDeadline;
        this.dispatchDeadline = dispatchDeadline;
    }

    @Override
    public Intent submit(Claim claim, ToolCall call) {
        String tenantId = claim.tenantId();
        synchronized (this) {
            String k = claim.tenantId() + "/" + claim.executionId() + "/" + call.key();
            Row existing = byKey.get(k);
            if (existing != null) {
                if (!existing.toolName.equals(call.toolName())
                        || !existing.digest.equals(CanonicalDigest.of(call.arguments()))) {
                    throw new ReplayDivergenceException(
                            "Call " + call.key() + " differs from the one first made");
                }
                return new Intent(existing.id, existing.key);
            }
            if (!claims.holds(claim)) {
                throw new io.hensu.core.execution.RunPreemptedException(
                        "Claim " + claim + " no longer stands");
            }
            Row row = new Row(nextId++, claim, call, Instant.now().plus(dispatchDeadline));
            rows.put(row.id, row);
            byKey.put(k, row);
            signals.intent(tenantId);
            return new Intent(row.id, row.key);
        }
    }

    @Override
    public synchronized Optional<Settlement> settlement(Intent intent) {
        Row row = rows.get(intent.id());
        if (row == null) {
            return Optional.empty();
        }
        return switch (row.state) {
            case "DONE" -> Optional.of(row.settlement);
            case "CANCELLED" -> Optional.of(new Settlement("CANCELLED", null));
            default -> Optional.empty();
        };
    }

    @Override
    public synchronized List<Offer> offer(String tenantId, int max) {
        List<Offer> offers = new ArrayList<>();
        for (Row row : rows.values()) {
            if (offers.size() >= max) {
                break;
            }
            if (row.tenantId.equals(tenantId) && row.state.equals("PENDING")) {
                row.state = "OFFERED";
                row.token = UUID.randomUUID().toString();
                row.offerExpires = Instant.now().plus(ackDeadline);
                offers.add(
                        new Offer(
                                row.token,
                                ToolCall.id(row.executionId, row.key),
                                row.toolName,
                                row.arguments,
                                callDeadline.toMillis()));
            }
        }
        return offers;
    }

    @Override
    public synchronized Ack ack(String tenantId, String token) {
        Row row = byToken(tenantId, token);
        if (row == null) {
            return Ack.NOT_OFFERED;
        }
        if (row.state.equals("OFFERED") && Instant.now().isBefore(row.offerExpires)) {
            row.state = "STARTED";
            row.deadline = Instant.now().plus(callDeadline);
            return Ack.STARTED;
        }
        return row.state.equals("STARTED") ? Ack.STARTED : Ack.NOT_OFFERED;
    }

    @Override
    public Completion complete(String tenantId, String token, Settlement outcome) {
        long settled;
        synchronized (this) {
            Row row = byToken(tenantId, token);
            if (row == null) {
                return Completion.UNKNOWN;
            }
            if (!row.state.equals("STARTED")) {
                return row.state.equals("DONE") || row.state.equals("CANCELLED")
                        ? Completion.ALREADY_SETTLED
                        : Completion.UNKNOWN;
            }
            settle(row, outcome);
            settled = row.id;
        }
        signals.outcome(settled);
        return Completion.SETTLED;
    }

    @Override
    public void expireDeadlines() {
        List<Long> settled = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        synchronized (this) {
            Instant now = Instant.now();
            for (Row row : rows.values()) {
                switch (row.state) {
                    case "OFFERED" -> {
                        if (!now.isBefore(row.offerExpires)) {
                            row.state = "PENDING";
                            row.token = null;
                            pending.add(row.tenantId);
                        }
                    }
                    case "STARTED" -> {
                        if (!now.isBefore(row.deadline)) {
                            settle(row, new Settlement("TIMEOUT", null));
                            settled.add(row.id);
                        }
                    }
                    case "PENDING" -> {
                        if (!now.isBefore(row.dispatchDeadline)) {
                            settle(row, new Settlement("UNAVAILABLE", null));
                            settled.add(row.id);
                        }
                    }
                    default -> {}
                }
            }
        }
        pending.forEach(signals::intent);
        settled.forEach(signals::outcome);
    }

    /// Follows an execution that paused or finished: settled calls drop their results, and
    /// when it finished, the intents no relay has taken are cancelled. Intents made under a
    /// later claim – a resume that came first – are left alone.
    ///
    /// @param claim the claim the execution settled under, not null
    /// @param finished true when it finished, false when it paused
    public void settled(Claim claim, boolean finished) {
        List<Long> cancelled = new ArrayList<>();
        synchronized (this) {
            for (Row row : rows.values()) {
                if (!row.tenantId.equals(claim.tenantId())
                        || !row.executionId.equals(claim.executionId())
                        || row.epoch > claim.epoch()) {
                    continue;
                }
                if (row.state.equals("DONE")) {
                    row.settlement = new Settlement(row.settlement.status(), null);
                } else if (finished
                        && (row.state.equals("PENDING") || row.state.equals("OFFERED"))) {
                    row.state = "CANCELLED";
                    row.token = null;
                    row.arguments = Map.of();
                    cancelled.add(row.id);
                }
            }
        }
        cancelled.forEach(signals::outcome);
    }

    private void settle(Row row, Settlement outcome) {
        row.state = "DONE";
        row.settlement = outcome;
        row.arguments = Map.of();
    }

    private Row byToken(String tenantId, String token) {
        for (Row row : rows.values()) {
            if (token.equals(row.token) && row.tenantId.equals(tenantId)) {
                return row;
            }
        }
        return null;
    }
}
