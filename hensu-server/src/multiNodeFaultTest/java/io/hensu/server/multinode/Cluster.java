package io.hensu.server.multinode;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/// Postgres, Toxiproxy and two server slots on one Docker network, plus the load
/// balancer and API client the workload and the relays use.
///
/// Every server reaches Postgres through its own Toxiproxy route, so a node–DB
/// partition cuts exactly one node. The load balancer is deliberately ordinary: it
/// picks any node that has not been stopped – a paused node included, since a load
/// balancer cannot tell a frozen process from a slow one.
final class Cluster implements AutoCloseable {

    static final String DB_USER = "hensu";
    static final String DB_PASSWORD = "hensu";
    static final String WORKFLOW = "fault-test-chain";
    static final List<String> SLOTS = List.of("n1", "n2");

    private static final ObjectMapper JSON = new ObjectMapper();

    final FaultTestConfig config;
    final History history;
    final Jwt jwt = new Jwt();
    final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

    private final Network network = Network.newNetwork();
    private final PostgreSQLContainer postgres;
    private final ToxiproxyContainer toxiproxy;
    private final Map<String, Proxy> routes = new ConcurrentHashMap<>();
    private final Map<String, ServerNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, Integer> skews;
    private final Map<String, Integer> generation = new ConcurrentHashMap<>();
    private final Map<ServerNode, Boolean> ready = new ConcurrentHashMap<>();
    private final Path workDir;
    private final ScheduledExecutorService healthChecks =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("lb-health").factory());

    Cluster(FaultTestConfig config, History history, Map<String, Integer> skews, Path workDir) {
        this.config = config;
        this.history = history;
        this.skews = skews;
        this.workDir = workDir;
        this.postgres =
                new PostgreSQLContainer("postgres:17-alpine")
                        .withDatabaseName("hensu")
                        .withUsername(DB_USER)
                        .withPassword(DB_PASSWORD)
                        .withNetwork(network)
                        .withNetworkAliases("pg");
        this.toxiproxy =
                new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0")
                        .withNetwork(network)
                        .withNetworkAliases("toxiproxy");
    }

    void start() throws Exception {
        postgres.start();
        toxiproxy.start();
        ToxiproxyClient client =
                new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        for (int i = 0; i < SLOTS.size(); i++) {
            String slot = SLOTS.get(i);
            routes.put(slot, client.createProxy("pg-" + slot, "0.0.0.0:" + (8666 + i), "pg:5432"));
        }
        jwt.writePublicKey(workDir.resolve("keys"));
        copyStubs(workDir.resolve("stubs"));

        startNode(SLOTS.getFirst());
        try (Connection c = db();
                Statement s = c.createStatement()) {
            s.execute(resource("multinode/audit.sql"));
        }
        for (String slot : SLOTS.subList(1, SLOTS.size())) {
            startNode(slot);
        }
        healthChecks.scheduleWithFixedDelay(this::checkHealth, 0, 1, TimeUnit.SECONDS);
    }

    /// One round of the load balancer's health checks, `/q/health/ready` on every node that
    /// has not been stopped; runs a second after the previous round ends.
    private void checkHealth() {
        for (ServerNode node : nodes()) {
            if (node.state() == ServerNode.State.STOPPED) {
                ready.remove(node);
                continue;
            }
            Reply reply =
                    call(node, "health", "GET", "/q/health/ready", null, Duration.ofMillis(800));
            ready.put(node, reply.status() == 200);
        }
    }

    // ---------------------------------------------------------------- nodes

    void startNode(String slot) {
        int gen = generation.merge(slot, 1, Integer::sum);
        String id = gen == 1 ? slot : slot + "-" + gen;
        int port = 8666 + SLOTS.indexOf(slot);
        ServerNode node =
                new ServerNode(
                        slot,
                        id,
                        skews.getOrDefault(slot, 0),
                        network,
                        config.serverDir(),
                        workDir.resolve("stubs"),
                        workDir.resolve("keys"),
                        "jdbc:postgresql://toxiproxy:"
                                + port
                                + "/hensu?socketTimeout=15&connectTimeout=5&tcpKeepAlive=true",
                        settings());
        node.start();
        ServerNode previous = nodes.put(slot, node);
        if (previous != null) {
            saveLogs(previous);
            previous.remove();
        }
        history.fault("node-up", node.id, node.toString());
    }

    List<ServerNode> nodes() {
        return SLOTS.stream().map(nodes::get).filter(Objects::nonNull).toList();
    }

    /// Whether a slot's node is running and reaches the DB – a target worth faulting.
    boolean available(String slot) {
        ServerNode node = nodes.get(slot);
        Proxy route = routes.get(slot);
        return node != null && node.state() == ServerNode.State.RUNNING && route.isEnabled();
    }

    /// Whether every slot's node is running and reaches the DB.
    boolean allAvailable() {
        return SLOTS.stream().allMatch(this::available);
    }

    /// The load balancer: any node that has not been stopped and passed its last health
    /// check, or any node that has not been stopped when none passed. Between checks a
    /// node that just failed still receives traffic, as with a real load balancer.
    ServerNode pick(Random random) {
        List<ServerNode> routable =
                nodes().stream().filter(n -> n.state() != ServerNode.State.STOPPED).toList();
        if (routable.isEmpty()) {
            throw new IllegalStateException("No routable node");
        }
        List<ServerNode> healthy =
                routable.stream().filter(n -> ready.getOrDefault(n, true)).toList();
        List<ServerNode> pool = healthy.isEmpty() ? routable : healthy;
        return pool.get(random.nextInt(pool.size()));
    }

    void pause(String slot) {
        ServerNode node = nodes.get(slot);
        if (node.state() == ServerNode.State.RUNNING) {
            node.pause();
            history.fault("pause", node.id, "");
        }
    }

    void unpause(String slot) {
        ServerNode node = nodes.get(slot);
        if (node.state() == ServerNode.State.PAUSED) {
            node.unpause();
            history.fault("unpause", node.id, "");
        }
    }

    void partition(String slot) throws IOException {
        Proxy route = routes.get(slot);
        if (route.isEnabled()) {
            route.disable();
            history.fault("partition", nodes.get(slot).id, "node-db");
        }
    }

    void heal(String slot) throws IOException {
        Proxy route = routes.get(slot);
        if (!route.isEnabled()) {
            route.enable();
            history.fault("heal", nodes.get(slot).id, "node-db");
        }
    }

    void crash(String slot) {
        ServerNode node = nodes.get(slot);
        if (node.state() != ServerNode.State.STOPPED) {
            history.fault("crash", node.id, "SIGKILL");
            node.kill();
        }
    }

    void terminate(String slot) {
        ServerNode node = nodes.get(slot);
        if (node.state() != ServerNode.State.STOPPED) {
            history.fault("terminate", node.id, "SIGTERM");
            node.terminate();
        }
    }

    /// Heals every fault, so the quiet period can show what recovers on its own.
    void healAll() throws Exception {
        for (String slot : SLOTS) {
            heal(slot);
            ServerNode node = nodes.get(slot);
            if (node.state() == ServerNode.State.PAUSED) {
                unpause(slot);
            } else if (node.state() == ServerNode.State.STOPPED) {
                startNode(slot);
            }
        }
    }

    // ------------------------------------------------------------------ API

    record Reply(int status, String body) {}

    Reply call(
            ServerNode node,
            String tenant,
            String method,
            String path,
            String body,
            Duration timeout) {
        return call(node, tenant, method, path, body, timeout, Map.of());
    }

    Reply call(
            ServerNode node,
            String tenant,
            String method,
            String path,
            String body,
            Duration timeout,
            Map<String, String> headers) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(node.base().resolve(path))
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + jwt.token(tenant))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json");
        headers.forEach(request::header);
        request =
                switch (method) {
                    case "GET" -> request.GET();
                    case "PUT" -> request.PUT(HttpRequest.BodyPublishers.ofString(body));
                    default ->
                            request.POST(
                                    HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                };
        try {
            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (IOException e) {
            return new Reply(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(-1, "interrupted");
        }
    }

    void pushWorkflow(String tenant) throws IOException {
        String workflow = resource("multinode/workflow.json");
        Reply reply =
                call(
                        nodes.get(SLOTS.getFirst()),
                        tenant,
                        "POST",
                        "/api/v1/workflows",
                        workflow,
                        Duration.ofSeconds(10));
        if (reply.status() / 100 != 2) {
            throw new IllegalStateException("Workflow push failed: " + reply);
        }
    }

    /// Starts one execution through the load balancer the way a careful client does:
    /// retrying on another node, with one idempotency key for the logical start sent with
    /// every retry; every answer is kept in the history. The key also goes into the
    /// context, so the checker can count the executions each logical start created –
    /// against a server that ignores the header as well as one that honours it.
    void startExecution(String tenant, Random random) {
        String startRef = new UUID(random.nextLong(), random.nextLong()).toString();
        String body =
                "{\"workflowId\":\""
                        + WORKFLOW
                        + "\",\"context\":{\"stub_scenario\":\"chain\",\"start_ref\":\""
                        + startRef
                        + "\"}}";
        for (int attempt = 0; attempt < 4; attempt++) {
            ServerNode node = pick(random);
            Reply reply =
                    call(
                            node,
                            tenant,
                            "POST",
                            "/api/v1/executions",
                            body,
                            Duration.ofSeconds(4),
                            Map.of("Idempotency-Key", startRef));
            String executionId = null;
            if (reply.status() == 202) {
                try {
                    executionId = JSON.readTree(reply.body()).path("executionId").asText(null);
                } catch (IOException ignored) {
                    // recorded below with the raw body
                }
            }
            history.api(tenant, node.id, reply.status(), executionId, abbreviate(reply.body()));
            if (executionId != null) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------- DB

    Connection db() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), DB_USER, DB_PASSWORD);
    }

    List<Map<String, Object>> query(String sql) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = db();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    Object value = rs.getObject(i);
                    if (value instanceof java.sql.Timestamp ts) {
                        value = ts.toInstant().toEpochMilli();
                    } else if (value instanceof java.time.OffsetDateTime odt) {
                        value = odt.toInstant().toEpochMilli();
                    } else if (value != null
                            && value.getClass().getName().equals("org.postgresql.util.PGobject")) {
                        value = JSON.readTree(value.toString());
                    }
                    row.put(meta.getColumnLabel(i), value);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    // -------------------------------------------------------------- support

    /// Server settings: claim timing and intent deadlines scaled down so that faults bite
    /// within a run.
    static Map<String, String> settings() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HENSU_CLAIM_TTL", FaultTestConfig.CLAIM_TTL.toSeconds() + "s");
        env.put(
                "HENSU_CLAIM_RENEW_INTERVAL",
                FaultTestConfig.CLAIM_RENEW_INTERVAL.toSeconds() + "s");
        env.put("HENSU_CLAIM_PICKUP_INTERVAL", "1s");
        env.put("HENSU_MCP_ACK_DEADLINE", "2s");
        env.put("HENSU_MCP_CALL_DEADLINE", FaultTestConfig.CALL_DEADLINE.toSeconds() + "s");
        env.put("HENSU_MCP_DISPATCH_DEADLINE", "30s");
        env.put("HENSU_MCP_POLL_WAIT", "2s");
        return env;
    }

    static String resource(String name) throws IOException {
        try (InputStream in = Cluster.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("Missing fault-test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void copyStubs(Path dir) throws IOException {
        Path scenario = dir.resolve("chain");
        Files.createDirectories(scenario);
        for (String node : List.of("n1", "n2", "n3")) {
            Files.writeString(
                    scenario.resolve(node + ".txt"),
                    resource("multinode/stubs/chain/" + node + ".txt"));
        }
    }

    static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    /// Keeps a node's log under the seed's work directory before its container goes.
    void saveLogs(ServerNode node) {
        try {
            Path dir = workDir.resolve("logs");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(node.id + ".log"), node.logs());
        } catch (IOException e) {
            history.fault("logs-lost", node.id, e.getMessage());
        }
    }

    void saveAllLogs() {
        nodes().forEach(this::saveLogs);
    }

    @Override
    public void close() {
        healthChecks.shutdownNow();
        nodes.values().forEach(ServerNode::remove);
        toxiproxy.stop();
        postgres.stop();
        network.close();
    }
}
