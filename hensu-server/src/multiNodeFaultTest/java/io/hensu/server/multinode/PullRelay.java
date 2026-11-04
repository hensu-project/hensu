package io.hensu.server.multinode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/// A tenant's tool relay speaking the journaled pull protocol, following its contract exactly:
/// publish the catalog, long-poll any node, acknowledge an offer before executing it,
/// execute only on a 204, and retry `ack` and `complete` until a node answers.
///
/// Every request goes through the load balancer on its own, so consecutive requests of
/// one call routinely reach different nodes. The relay de-duplicates nothing: if the
/// protocol ever let it execute a call twice, the history would show it.
///
/// A share of responses is dropped after the server processed the request, the way a
/// load balancer that times out a forwarded request drops it: a poll's offers are lost
/// and must lapse back to the queue, and an `ack` or `complete` is retried on whichever
/// node the balancer picks next. A smaller share of offers is acknowledged only after the
/// offer has lapsed – a relay stalled between poll and ack – which the server must refuse,
/// and the call must still run exactly once under a later offer.
///
/// Every request it sends and every call it executes goes into the history with the node
/// that served it, so the checker can tell which claim issued the call.
final class PullRelay implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERSION = "fault-test-v1";
    private static final String CATALOG =
            "{\"version\":\""
                    + VERSION
                    + "\",\"tools\":[{\"name\":\"record\",\"description\":\"Records one workflow"
                    + " step\",\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                    + "\"exec\":{\"type\":\"string\"},\"node\":{\"type\":\"string\"}},"
                    + "\"required\":[\"exec\",\"node\"]}}]}";
    private static final int POLLERS = 2;
    private static final double RESPONSE_LOSS = 0.05;
    private static final double LATE_ACK = 0.03;
    /// Longer than the configured ack deadline (2 s) plus one deadline sweep (1 s).
    private static final long LATE_ACK_MILLIS = 3500;
    /// How long one `record` call takes at the tenant: long enough that a fault routinely
    /// lands while calls are in flight.
    private static final int TOOL_LATENCY_MIN_MILLIS = 200;
    private static final int TOOL_LATENCY_SPREAD_MILLIS = 1300;

    private final Cluster cluster;
    private final String tenant;
    private final History history;
    private final Random random;
    private volatile boolean closed;

    PullRelay(Cluster cluster, String tenant, History history, long seed) {
        this.cluster = cluster;
        this.tenant = tenant;
        this.history = history;
        this.random = new Random(seed);
    }

    /// Publishes the catalog and starts the pollers in the background.
    void start() {
        publish();
        for (int i = 0; i < POLLERS; i++) {
            Thread.ofVirtual().name("relay-" + tenant + "-" + i).start(this::loop);
        }
    }

    private void publish() {
        while (!closed) {
            ServerNode node = pick();
            Cluster.Reply reply =
                    cluster.call(
                            node, tenant, "PUT", "/mcp/v1/catalog", CATALOG, Duration.ofSeconds(4));
            history.connect(tenant, node.id, reply.status(), "catalog");
            if (reply.status() == 204) {
                return;
            }
            sleep(200);
        }
    }

    private void loop() {
        while (!closed) {
            ServerNode node = pick();
            Cluster.Reply reply =
                    cluster.call(
                            node,
                            tenant,
                            "POST",
                            "/mcp/v1/poll",
                            "{\"catalogVersion\":\"" + VERSION + "\",\"max\":4,\"waitMs\":1500}",
                            Duration.ofSeconds(5));
            if (reply.status() == 409) {
                publish();
                continue;
            }
            if (reply.status() != 200) {
                history.connect(tenant, node.id, reply.status(), "poll failed");
                sleep(200);
                continue;
            }
            if (lose(node, "poll")) {
                continue;
            }
            try {
                for (JsonNode task : JSON.readTree(reply.body()).path("tasks")) {
                    Thread.ofVirtual().start(() -> handle(node, task));
                }
            } catch (Exception e) {
                history.connect(tenant, node.id, -1, "unreadable poll response");
            }
        }
    }

    private void handle(ServerNode offeredBy, JsonNode task) {
        String token = task.path("token").asText();
        String key = task.path("key").asText();
        Map<String, Object> arguments = new LinkedHashMap<>();
        task.path("arguments")
                .properties()
                .forEach(f -> arguments.put(f.getKey(), f.getValue().asText()));
        String execution = String.valueOf(arguments.get("exec"));
        history.add(
                new History.Event(
                        History.now(),
                        "received",
                        tenant,
                        offeredBy.id,
                        execution,
                        token,
                        key,
                        task.path("tool").asText(),
                        arguments,
                        0,
                        null));

        boolean late;
        synchronized (random) {
            late = random.nextDouble() < LATE_ACK;
        }
        if (late) {
            history.add(
                    new History.Event(
                            History.now(),
                            "late-ack",
                            tenant,
                            offeredBy.id,
                            execution,
                            token,
                            key,
                            null,
                            null,
                            0,
                            "ack delayed past the offer (injected)"));
            sleep(LATE_ACK_MILLIS);
        }
        int ack = untilAnswered("/mcp/v1/tasks/" + token + "/ack", "");
        history.add(
                new History.Event(
                        History.now(),
                        "acked",
                        tenant,
                        null,
                        execution,
                        token,
                        key,
                        null,
                        null,
                        ack,
                        null));
        if (ack != 204) {
            return; // not the current offer: the contract forbids executing it
        }
        sleep(toolLatencyMillis());
        history.add(
                new History.Event(
                        History.now(),
                        "executed",
                        tenant,
                        offeredBy.id,
                        execution,
                        token,
                        key,
                        "record",
                        arguments,
                        0,
                        null));
        int complete =
                untilAnswered(
                        "/mcp/v1/tasks/" + token + "/complete",
                        "{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"recorded "
                                + arguments.get("node")
                                + "\"}],\"isError\":false}}");
        history.add(
                new History.Event(
                        History.now(),
                        "answered",
                        tenant,
                        null,
                        execution,
                        token,
                        key,
                        null,
                        null,
                        complete,
                        "complete"));
    }

    /// Retries through the load balancer until some node gives a definite answer.
    private int untilAnswered(String path, String body) {
        long backoff = 100;
        while (!closed) {
            ServerNode node = pick();
            Cluster.Reply reply =
                    cluster.call(node, tenant, "POST", path, body, Duration.ofSeconds(4));
            if (reply.status() > 0 && reply.status() < 500 && !lose(node, path)) {
                return reply.status();
            }
            sleep(backoff);
            backoff = Math.min(backoff * 2, 1000);
        }
        return -1;
    }

    /// Decides whether to drop a response the server already acted on, and records it.
    private boolean lose(ServerNode node, String what) {
        boolean lost;
        synchronized (random) {
            lost = random.nextDouble() < RESPONSE_LOSS;
        }
        if (lost) {
            history.connect(tenant, node.id, -2, "response lost (injected): " + what);
        }
        return lost;
    }

    private ServerNode pick() {
        synchronized (random) {
            return cluster.pick(random);
        }
    }

    private int toolLatencyMillis() {
        synchronized (random) {
            return TOOL_LATENCY_MIN_MILLIS + random.nextInt(TOOL_LATENCY_SPREAD_MILLIS);
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
