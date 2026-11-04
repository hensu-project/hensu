package io.hensu.server.multinode;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/// Checks one seed's history against the invariants of section 1.
///
/// Claims are reconstructed from the DB audit – the only record whose clock every
/// participant shares – as the epochs the rows carry.
final class Checker {

    static final long STEAL_MARGIN_MS = 500;
    static final List<String> SAFETY = List.of("S3", "S3a", "S5", "S6", "S8");
    static final List<String> LIVENESS = List.of("L1", "L3", "L4");

    /// What the checker reads.
    record Observed(
            List<History.Event> events,
            List<Map<String, Object>> audit,
            List<Map<String, Object>> finalRows,
            List<Map<String, Object>> toolAudit,
            List<Map<String, Object>> intents) {}

    record Finding(String invariant, String execution, String detail) {}

    record ClaimEvent(long at, long epoch, String owner, boolean fromLive, long previousRenewal) {}

    record Interval(long epoch, String owner, long from, long to) {}

    /// Per-execution claim history.
    static final class Timeline {
        final List<ClaimEvent> claims = new ArrayList<>();
        final List<Interval> intervals = new ArrayList<>();
        long terminalAt = Long.MAX_VALUE;

        String ownerAt(long at) {
            for (Interval interval : intervals) {
                if (interval.from() <= at && at < interval.to()) {
                    return interval.owner();
                }
            }
            return null;
        }

        long epochAt(long at) {
            long epoch = 0;
            for (ClaimEvent claim : claims) {
                if (claim.at() <= at) {
                    epoch = claim.epoch();
                }
            }
            return epoch;
        }
    }

    record Report(Map<String, List<Finding>> findings, Map<String, Object> metrics) {

        int count(String invariant) {
            return findings.getOrDefault(invariant, List.of()).size();
        }

        int safetyViolations() {
            return SAFETY.stream().mapToInt(this::count).sum();
        }
    }

    private final Observed observed;
    private final Map<String, Timeline> timelines = new HashMap<>();
    private final Map<String, List<Finding>> findings = new LinkedHashMap<>();
    private final Map<String, Object> metrics = new LinkedHashMap<>();

    Checker(Observed observed) {
        this.observed = observed;
        for (String invariant : List.of("S3", "S3a", "S5", "S6", "S8", "L1", "L2", "L3", "L4")) {
            findings.put(invariant, new ArrayList<>());
        }
    }

    Report check() {
        buildTimelines();
        checkStaleEffects();
        checkAtMostOnce();
        checkLiveSteal();
        checkOutcomes();
        checkIdempotentStart();
        checkStranding();
        checkRouting();
        checkToolOutcomes();
        checkRecovery();
        return new Report(findings, metrics);
    }

    // ------------------------------------------------------------- timelines

    private void buildTimelines() {
        Map<String, long[]> lastRenewal = new HashMap<>();
        for (Map<String, Object> row : observed.audit()) {
            String execution = (String) row.get("execution_id");
            long at = (Long) row.get("at");
            String op = (String) row.get("op");
            JsonNode before = (JsonNode) row.get("old_row");
            JsonNode after = (JsonNode) row.get("new_row");
            Timeline t = timelines.computeIfAbsent(execution, _ -> new Timeline());
            long[] renewal = lastRenewal.computeIfAbsent(execution, _ -> new long[] {0});
            if (op.equals("DELETE") || after == null) {
                close(t, at);
                continue;
            }
            if ("DONE".equals(text(after, "run_state")) && t.terminalAt == Long.MAX_VALUE) {
                t.terminalAt = at;
            }
            long epoch = after.path("claim_epoch").asLong();
            long priorEpoch = before == null ? 0 : before.path("claim_epoch").asLong();
            boolean running = "RUNNING".equals(text(after, "run_state"));
            boolean wasRunning = before != null && "RUNNING".equals(text(before, "run_state"));
            if (running && epoch > priorEpoch) {
                claim(t, at, epoch, text(after, "claim_owner"), wasRunning, renewal[0]);
                renewal[0] = at;
            } else if (running
                    && !Objects.equals(
                            text(before, "claim_expires_at"), text(after, "claim_expires_at"))) {
                renewal[0] = at;
            } else if (!running && wasRunning) {
                close(t, at);
            }
        }
    }

    private static void claim(
            Timeline t, long at, long epoch, String owner, boolean fromLive, long previousRenewal) {
        close(t, at);
        t.claims.add(new ClaimEvent(at, epoch, owner, fromLive, previousRenewal));
        t.intervals.add(new Interval(epoch, owner, at, Long.MAX_VALUE));
    }

    private static void close(Timeline t, long at) {
        if (!t.intervals.isEmpty()) {
            Interval open = t.intervals.getLast();
            if (open.to() == Long.MAX_VALUE) {
                t.intervals.set(
                        t.intervals.size() - 1,
                        new Interval(open.epoch(), open.owner(), open.from(), at));
            }
        }
    }

    // ---------------------------------------------------------------- safety

    /// S3: no call takes effect that a superseded claim issued.
    private void checkStaleEffects() {
        Map<String, Map<String, Object>> intents = new HashMap<>();
        // A relay sees each call under its execution-qualified key.
        observed.intents()
                .forEach(i -> intents.put(i.get("execution_id") + "/" + i.get("call_key"), i));
        for (History.Event e : events("executed")) {
            Map<String, Object> intent = intents.get(e.key());
            if (intent == null) {
                add(
                        "S3",
                        e.execution(),
                        "relay executed key " + e.key() + " that no intent carries");
                continue;
            }
            long createdAt = (Long) intent.get("created_at");
            long createdEpoch = ((Number) intent.get("created_epoch")).longValue();
            Timeline t = timelines.get((String) intent.get("execution_id"));
            if (t != null && t.epochAt(createdAt) > createdEpoch) {
                add(
                        "S3",
                        e.execution(),
                        "intent "
                                + e.key()
                                + " committed under epoch "
                                + createdEpoch
                                + " after epoch "
                                + t.epochAt(createdAt)
                                + " was claimed");
            }
        }
    }

    /// S3a: each logical call – one per workflow node per execution – runs at most once.
    private void checkAtMostOnce() {
        Map<String, List<History.Event>> byCall =
                events("executed").stream()
                        .collect(
                                Collectors.groupingBy(
                                        e ->
                                                e.tenant()
                                                        + "/"
                                                        + e.execution()
                                                        + "/"
                                                        + e.args().get("node"),
                                        TreeMap::new,
                                        Collectors.toList()));
        byCall.forEach(
                (call, runs) -> {
                    if (runs.size() > 1) {
                        add(
                                "S3a",
                                runs.getFirst().execution(),
                                call.substring(call.lastIndexOf('/') + 1)
                                        + " executed "
                                        + runs.size()
                                        + " times, from "
                                        + runs.stream().map(History.Event::node).toList());
                    }
                });
        metrics.put("callsExecuted", events("executed").size());
        metrics.put("logicalCalls", byCall.size());

        // An offer acknowledged after it lapsed could have been offered again meanwhile,
        // and two acknowledged offers of one call is a duplicate waiting to happen.
        Map<String, Integer> ackStatus = new HashMap<>();
        events("acked").forEach(e -> ackStatus.put(e.request(), e.status()));
        List<History.Event> late = events("late-ack");
        long refused = 0;
        for (History.Event e : late) {
            int status = ackStatus.getOrDefault(e.request(), -1);
            if (status == 204) {
                add("S3a", e.execution(), "offer for " + e.key() + " acknowledged after it lapsed");
            } else if (status == 409) {
                refused++;
            }
        }
        metrics.put("lateAcks", late.size());
        metrics.put("lateAcksRefused", refused);
    }

    /// S5: no claim supersedes a holder that renewed within the lease period of DB time.
    private void checkLiveSteal() {
        long stale = FaultTestConfig.CLAIM_TTL.toMillis();
        timelines.forEach(
                (execution, t) -> {
                    for (int i = 1; i < t.claims.size(); i++) {
                        ClaimEvent claim = t.claims.get(i);
                        long age = claim.at() - claim.previousRenewal();
                        if (claim.fromLive() && age < stale - STEAL_MARGIN_MS) {
                            add(
                                    "S5",
                                    execution,
                                    claim.owner()
                                            + " took epoch "
                                            + claim.epoch()
                                            + " from "
                                            + t.claims.get(i - 1).owner()
                                            + " whose last renewal was "
                                            + age
                                            + " ms earlier (lease "
                                            + stale
                                            + " ms)");
                        }
                    }
                });
        metrics.put("claims", timelines.values().stream().mapToInt(t -> t.claims.size()).sum());
    }

    /// S6: a request is answered into the server at most once.
    private void checkOutcomes() {
        Map<String, Long> accepted =
                events("answered").stream()
                        .filter(e -> e.status() / 100 == 2)
                        .collect(
                                Collectors.groupingBy(
                                        History.Event::request, Collectors.counting()));
        accepted.forEach(
                (request, n) -> {
                    if (n > 1) {
                        add("S6", null, "request " + request + " accepted " + n + " answers");
                    }
                });
    }

    // -------------------------------------------------------------- liveness

    /// S8: each logical start – one idempotency key, sent with every retry – created at
    /// most one execution.
    private void checkIdempotentStart() {
        Map<String, List<String>> byStart = new HashMap<>();
        for (Map<String, Object> row : observed.finalRows()) {
            Object ref = row.get("start_ref");
            if (ref != null) {
                byStart.computeIfAbsent(ref.toString(), _ -> new ArrayList<>())
                        .add((String) row.get("execution_id"));
            }
        }
        long duplicates = 0;
        for (Map.Entry<String, List<String>> start : byStart.entrySet()) {
            if (start.getValue().size() > 1) {
                duplicates += start.getValue().size() - 1;
                add(
                        "S8",
                        start.getValue().getFirst(),
                        "one start created "
                                + start.getValue().size()
                                + " executions: "
                                + start.getValue());
            }
        }
        metrics.put("duplicateExecutions", duplicates);
    }

    /// L1: every accepted execution ends terminal or paused once faults heal.
    private void checkStranding() {
        Map<String, Map<String, Object>> rows = new HashMap<>();
        observed.finalRows().forEach(r -> rows.put((String) r.get("execution_id"), r));
        List<History.Event> accepted =
                events("api").stream()
                        .filter(e -> e.status() == 202 && e.execution() != null)
                        .toList();
        int completed = 0;
        for (History.Event start : accepted) {
            Map<String, Object> row = rows.get(start.execution());
            if (row == null) {
                add(
                        "L1",
                        start.execution(),
                        "accepted by " + start.node() + " but never written – lost");
                continue;
            }
            if (!Set.of("DONE", "PAUSED").contains(String.valueOf(row.get("run_state")))) {
                add("L1", start.execution(), "still " + describe(row) + " after the quiet period");
            }
            if ("completed".equals(String.valueOf(row.get("checkpoint_reason")))) {
                completed++;
            }
        }
        metrics.put("accepted", accepted.size());
        metrics.put("completed", completed);
        metrics.put(
                "failed",
                rows.values().stream()
                        .filter(r -> "failed".equals(String.valueOf(r.get("checkpoint_reason"))))
                        .count());
    }

    /// L3: nothing is refused or lost because of the node it reached.
    ///
    /// A refusal that depends on routing – a 409 or 404 because the request reached the
    /// wrong node – counts. A sick node's 5xx or silence does not: it fails its readiness check,
    /// the load balancer moves on, and the request is retried; those are reported as a metric.
    private void checkRouting() {
        int unavailable = 0;
        for (History.Event e : events("api")) {
            if (e.status() == 202) {
                continue;
            }
            if (e.status() >= 500 || e.status() == -1) {
                unavailable++;
            } else {
                add(
                        "L3",
                        e.execution(),
                        "start on " + e.node() + " answered " + e.status() + ": " + e.detail());
            }
        }
        metrics.put("startsOnUnavailableNode", unavailable);
        for (History.Event e : events("answered")) {
            if (e.status() == 404) {
                add(
                        "L3",
                        null,
                        "answer to "
                                + e.request()
                                + " ("
                                + e.detail()
                                + ") reached "
                                + e.node()
                                + " and was refused 404");
            }
        }
    }

    /// L4: no routing fault reaches the agent as a tool outcome.
    private void checkToolOutcomes() {
        for (Map<String, Object> row : observed.toolAudit()) {
            String status = String.valueOf(row.get("status"));
            if (!"SUCCESS".equals(status)) {
                add(
                        "L4",
                        (String) row.get("execution_id"),
                        status
                                + " at "
                                + row.get("node_id")
                                + ": "
                                + Cluster.abbreviate(String.valueOf(row.get("output"))));
            }
        }
        Map<String, Long> effects =
                events("executed").stream()
                        .collect(
                                Collectors.groupingBy(
                                        History.Event::execution, Collectors.counting()));
        for (Map<String, Object> row : observed.finalRows()) {
            boolean done =
                    "DONE".equals(String.valueOf(row.get("run_state")))
                            && "completed".equals(String.valueOf(row.get("checkpoint_reason")));
            String execution = (String) row.get("execution_id");
            long n = effects.getOrDefault(execution, 0L);
            if (done && n < 3) {
                add(
                        "L4",
                        execution,
                        "completed with "
                                + n
                                + " of 3 tool effects – the agent was handed failures");
            }
        }
    }

    /// L2: how long a crashed or stopped node's executions waited for a new claim.
    private void checkRecovery() {
        List<Long> latencies = new ArrayList<>();
        for (History.Event fault : events("fault")) {
            if (!(fault.detail().startsWith("crash") || fault.detail().startsWith("terminate"))) {
                continue;
            }
            timelines.forEach(
                    (execution, t) -> {
                        String owner = t.ownerAt(fault.at());
                        if (!fault.node().equals(owner)) {
                            return;
                        }
                        t.claims.stream()
                                .filter(c -> c.at() > fault.at())
                                .findFirst()
                                .ifPresentOrElse(
                                        c -> latencies.add(c.at() - fault.at()),
                                        () -> {
                                            if (t.terminalAt == Long.MAX_VALUE) {
                                                add(
                                                        "L2",
                                                        execution,
                                                        "never reclaimed after "
                                                                + fault.detail()
                                                                + " of "
                                                                + fault.node());
                                            }
                                        });
                    });
        }
        metrics.put("recoveryMillis", latencies);
        metrics.put(
                "recoveryMaxMillis", latencies.stream().mapToLong(Long::longValue).max().orElse(0));
    }

    // --------------------------------------------------------------- support

    private List<History.Event> events(String kind) {
        return observed.events().stream().filter(e -> e.kind().equals(kind)).toList();
    }

    private void add(String invariant, String execution, String detail) {
        findings.get(invariant).add(new Finding(invariant, execution, detail));
    }

    private static String text(JsonNode row, String field) {
        if (row == null) {
            return null;
        }
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String describe(Map<String, Object> row) {
        return row.entrySet().stream()
                .filter(
                        e ->
                                Set.of(
                                                "run_state",
                                                "claim_owner",
                                                "claim_epoch",
                                                "checkpoint_reason",
                                                "current_node_id")
                                        .contains(e.getKey()))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    static String markdown(Collection<Report> reports, List<String> labels) {
        StringBuilder md = new StringBuilder("# Fault-injection test\n\n| Seed | ");
        List<String> columns = List.of("S3", "S3a", "S5", "S6", "S8", "L1", "L2", "L3", "L4");
        md.append(String.join(" | ", columns))
                .append(
                        " | accepted | completed | claims | calls | late acks refused"
                                + " | max recovery ms |\n|");
        md.repeat("---|", columns.size() + 7).append("\n");
        int i = 0;
        for (Report r : reports) {
            md.append("| ").append(labels.get(i++)).append(" | ");
            for (String c : columns) {
                md.append(r.count(c)).append(" | ");
            }
            md.append(r.metrics().get("accepted"))
                    .append(" | ")
                    .append(r.metrics().get("completed"))
                    .append(" | ")
                    .append(r.metrics().get("claims"))
                    .append(" | ")
                    .append(r.metrics().get("callsExecuted"))
                    .append(" | ")
                    .append(r.metrics().getOrDefault("lateAcksRefused", 0))
                    .append("/")
                    .append(r.metrics().getOrDefault("lateAcks", 0))
                    .append(" | ")
                    .append(r.metrics().get("recoveryMaxMillis"))
                    .append(" |\n");
        }
        return md.toString();
    }
}
