package io.hensu.server.multinode;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.hensu.server.multinode.FaultSchedule.Fault;
import io.hensu.server.multinode.FaultTestConfig.Expectation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/// Runs randomized fault schedules against two server nodes and checks every
/// recorded history against the invariants of docs/multi-node-execution-protocol.md.
///
/// Per seed: start the cluster, connect one relay per tenant, run workload and faults
/// for the active period, heal everything, give the cluster a quiet period to recover,
/// then collect the DB audit and run the checker. Reports land in `build/multi-node-fault-test/`.
class FaultInjectionTest {

    private static final List<String> TENANTS = List.of("t1", "t2");
    private static final Duration QUIET = Duration.ofSeconds(45);

    @Test
    void historiesSatisfyTheInvariants() throws Exception {
        FaultTestConfig config = FaultTestConfig.fromSystemProperties();
        Path out = config.reportDir();
        Files.createDirectories(out);

        List<Checker.Report> reports = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (long seed : config.seeds()) {
            boolean skewed = seed % 2 == 0;
            Checker.Report report = runSeed(config, seed, skewed, out.resolve("seed-" + seed));
            reports.add(report);
            labels.add(seed + (skewed ? " (n2 +" + config.skewSeconds() + " s)" : ""));
            Files.writeString(out.resolve("summary.md"), Checker.markdown(reports, labels));
        }

        int safety = reports.stream().mapToInt(Checker.Report::safetyViolations).sum();
        int liveness =
                reports.stream()
                        .mapToInt(r -> Checker.LIVENESS.stream().mapToInt(r::count).sum())
                        .sum();
        // The run's report, not debug output: the task shows standard streams, so the table
        // reaches the console before any assertion below fails.
        System.out.println(Files.readString(out.resolve("summary.md")));
        if (config.expect() != Expectation.REPORT) {
            assertThat(safety).as("safety violations").isZero();
            // A seed in which nothing ran breaks no invariant: a server refusing every start,
            // or a relay that never receives a call, would otherwise pass.
            for (int i = 0; i < reports.size(); i++) {
                Map<String, Object> metrics = reports.get(i).metrics();
                assertThat(((Number) metrics.get("accepted")).intValue())
                        .as("seed %s: accepted executions", labels.get(i))
                        .isPositive();
                assertThat(((Number) metrics.get("completed")).intValue())
                        .as("seed %s: completed executions", labels.get(i))
                        .isPositive();
            }
        }
        if (config.expect() == Expectation.CLEAN) {
            assertThat(liveness).as("liveness violations (L1, L3, L4)").isZero();
        }
    }

    private Checker.Report runSeed(FaultTestConfig config, long seed, boolean skewed, Path dir)
            throws Exception {
        Files.createDirectories(dir);
        History history = new History();
        Map<String, Integer> skews = skewed ? Map.of("n2", config.skewSeconds()) : Map.of();
        List<Fault> faults = FaultSchedule.generate(seed, config.activeSeconds());
        writeJson(dir.resolve("schedule.json"), faults);

        try (Cluster cluster = new Cluster(config, history, skews, dir)) {
            cluster.start();
            for (String tenant : TENANTS) {
                cluster.pushWorkflow(tenant);
            }
            List<PullRelay> relays = new ArrayList<>();
            for (String tenant : TENANTS) {
                PullRelay relay =
                        new PullRelay(cluster, tenant, history, seed * 31 + tenant.hashCode());
                relays.add(relay);
                relay.start();
            }
            Thread.sleep(2000);

            AtomicBoolean active = new AtomicBoolean(true);
            Thread workload =
                    Thread.ofVirtual()
                            .name("workload")
                            .start(() -> workload(cluster, seed, active));
            try (ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(4)) {
                // Every fault starts before the active period ends, so dropping the delayed
                // ones on shutdown loses none in a normal run; it stops an aborted run from
                // injecting faults into a cluster that is being torn down.
                scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
                for (Fault fault : faults) {
                    scheduler.schedule(
                            () -> inject(cluster, fault), fault.atMillis(), TimeUnit.MILLISECONDS);
                }

                Thread.sleep(config.activeSeconds() * 1000L);
                active.set(false);
                workload.join();
                scheduler.shutdown();
                if (!scheduler.awaitTermination(180, TimeUnit.SECONDS)) {
                    history.fault("injection-overrun", null, "faults still running after 180 s");
                    // Interrupted, a fault cuts its wait short and undoes itself, so closing
                    // the scheduler cannot hang and the heal below does not race it.
                    scheduler.shutdownNow();
                }
            }

            cluster.healAll();
            history.fault(
                    "quiet", null, "faults healed; waiting up to " + QUIET.toSeconds() + " s");
            awaitSettled(cluster, history);

            Checker.Observed observed = observe(cluster, history);
            relays.forEach(PullRelay::close);
            cluster.saveAllLogs();
            history.write(dir.resolve("history.json"));
            Checker.Report report = new Checker(observed).check();
            writeJson(dir.resolve("report.json"), report);
            return report;
        }
    }

    private static void workload(Cluster cluster, long seed, AtomicBoolean active) {
        Random random = new Random(seed * 104729L);
        // One or two starts every 0.5 to 1.3 s keep six to ten executions in flight, so
        // every node fault interrupts some of them mid-call.
        while (active.get()) {
            int burst = 1 + random.nextInt(2);
            for (int i = 0; i < burst; i++) {
                String tenant = TENANTS.get(random.nextInt(TENANTS.size()));
                Random own = new Random(random.nextLong());
                Thread.ofVirtual().start(() -> cluster.startExecution(tenant, own));
            }
            sleep(500 + random.nextInt(800));
        }
    }

    private static void inject(Cluster cluster, Fault fault) {
        try {
            if (!cluster.allAvailable()) {
                // One impaired node at a time: the liveness invariants assume some node
                // reaches the DB, and overlapping node faults would test a cluster-wide
                // outage, which no node can serve, instead of the protocol.
                cluster.history.fault(
                        "skipped",
                        fault.target(),
                        fault.kind() + " while another node is impaired");
                return;
            }
            switch (fault.kind()) {
                case PAUSE -> {
                    cluster.pause(fault.target());
                    sleep(fault.durationMillis());
                    cluster.unpause(fault.target());
                }
                case PARTITION -> {
                    cluster.partition(fault.target());
                    sleep(fault.durationMillis());
                    cluster.heal(fault.target());
                }
                case CRASH -> {
                    cluster.crash(fault.target());
                    sleep(fault.durationMillis());
                    cluster.startNode(fault.target());
                }
                case DEPLOY -> {
                    cluster.terminate(fault.target());
                    cluster.startNode(fault.target());
                }
            }
        } catch (Exception e) {
            cluster.history.fault("injection-failed", fault.target(), fault.kind() + ": " + e);
        }
    }

    /// Waits until every accepted execution is terminal or paused, or the quiet period ends.
    private static void awaitSettled(Cluster cluster, History history) throws Exception {
        Set<String> accepted = new java.util.HashSet<>();
        long deadline = System.currentTimeMillis() + QUIET.toMillis();
        while (System.currentTimeMillis() < deadline) {
            accepted.clear();
            history.events().stream()
                    .filter(
                            e ->
                                    e.kind().equals("api")
                                            && e.status() == 202
                                            && e.execution() != null)
                    .forEach(e -> accepted.add(e.execution()));
            long open =
                    cluster
                            .query(
                                    "SELECT execution_id FROM runtime.execution_states"
                                            + " WHERE run_state NOT IN ('DONE','PAUSED')")
                            .stream()
                            .filter(r -> accepted.contains((String) r.get("execution_id")))
                            .count();
            long written =
                    cluster.query("SELECT count(*) AS n FROM runtime.execution_states").stream()
                            .mapToLong(r -> ((Number) r.get("n")).longValue())
                            .sum();
            if (open == 0 && written >= accepted.size()) {
                return;
            }
            sleep(2000);
        }
    }

    private static Checker.Observed observe(Cluster cluster, History history) throws Exception {
        List<Map<String, Object>> audit =
                cluster.query(
                        "SELECT id, at, op, tenant_id, execution_id, old_row, new_row FROM fault_test.lease_audit ORDER BY id");
        List<Map<String, Object>> finalRows =
                cluster.query(
                        "SELECT execution_id, run_state, checkpoint_reason,"
                                + " context->>'start_ref' AS start_ref"
                                + " FROM runtime.execution_states");
        List<Map<String, Object>> toolAudit =
                cluster.query(
                        "SELECT tenant_id, execution_id, node_id, tool_name, status, output, occurred_at FROM runtime.tool_audit ORDER BY id");
        List<Map<String, Object>> intents =
                cluster.query(
                        "SELECT tenant_id, execution_id, call_key, created_epoch, created_at, state, outcome_status FROM runtime.tool_intents");
        return new Checker.Observed(history.events(), audit, finalRows, toolAudit, intents);
    }

    private static void writeJson(Path file, Object value) throws Exception {
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(file.toFile(), value);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
