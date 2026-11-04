package io.hensu.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.InMemoryWorkflowStateRepository;
import io.hensu.core.tool.ToolCall;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.server.execution.Claim;
import io.hensu.server.execution.RunContext;
import io.hensu.server.execution.RunHandle;
import io.hensu.server.execution.RunRegistry;
import io.hensu.server.persistence.InMemoryExecutionStore;
import io.hensu.server.persistence.InMemoryMcpCatalogs;
import io.hensu.server.persistence.InMemoryToolIntents;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// The server's tool seam: a call becomes an intent under the run's claim, the relay pulls
/// it, and the run is handed whatever settled – once, however many times the run replays.
class McpToolProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InMemoryExecutionStore store;
    private ClusterSignals signals;
    private InMemoryToolIntents intents;
    private InMemoryMcpCatalogs catalogs;
    private RunRegistry registry;
    private McpToolProvider provider;
    private Claim claim;

    @BeforeEach
    void setUp() {
        store = new InMemoryExecutionStore(new InMemoryWorkflowStateRepository(), (_, _) -> {});
        signals = new ClusterSignals();
        intents =
                new InMemoryToolIntents(
                        store::holdsClaim,
                        signals,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5));
        catalogs = new InMemoryMcpCatalogs(JSON);
        registry = new RunRegistry(store, Duration.ofSeconds(30));
        provider =
                new McpToolProvider(
                        catalogs, intents, signals, Duration.ofSeconds(60), Duration.ofMinutes(5));
        claim =
                store.start(
                        "t1",
                        new HensuSnapshot(
                                "wf-1",
                                "exec-1",
                                "n1",
                                new HashMap<>(),
                                Map.of(),
                                null,
                                null,
                                Instant.now(),
                                "checkpoint",
                                0));
    }

    @Test
    void exposesTheCatalogTheTenantsRelayPublished() throws Exception {
        catalogs.publish(
                "t1",
                new McpCatalogs.Catalog(
                        "v1",
                        JSON.readTree(
                                "[{\"name\":\"read_file\",\"description\":\"Reads\","
                                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":"
                                        + "{\"path\":{\"type\":\"string\"}}}}]")));

        List<ToolDefinition> tools = TenantContext.runAs(TenantInfo.simple("t1"), provider::tools);

        assertThat(tools).extracting(ToolDefinition::name).containsExactly("read_file");
        assertThat(TenantContext.runAs(TenantInfo.simple("t2"), provider::tools)).isEmpty();
    }

    @Test
    void returnsTheOutcomeTheRelayCompletedTheIntentWith() throws Exception {
        Thread relay = relay("{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"42\"}]}}");

        ToolCallResult result = call(new ToolCall("0#1", "answer", Map.of("q", "life")));

        relay.join(5000);
        assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(result.output()).isEqualTo("42");
    }

    @Test
    void handsAReplayTheSettledOutcomeWithoutOfferingTheCallAgain() throws Exception {
        relay("{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"once\"}]}}");
        ToolCall call = new ToolCall("0#1", "charge", Map.of("amount", 10));
        call(call);

        // The recovered run reaches the same key: no relay is polling any more, and the
        // call must not be offered again.
        ToolCallResult replayed = call(call);

        assertThat(replayed.output()).isEqualTo("once");
        assertThat(intents.offer("t1", 10)).isEmpty();
    }

    @Test
    void reportsATimedOutCallAsOutcomeUnknown() throws Exception {
        intents =
                new InMemoryToolIntents(
                        store::holdsClaim,
                        signals,
                        Duration.ofSeconds(5),
                        Duration.ofMillis(100),
                        Duration.ofSeconds(5));
        provider =
                new McpToolProvider(
                        catalogs, intents, signals, Duration.ofMillis(100), Duration.ofMinutes(5));
        Thread relay =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    ToolIntents.Offer offer = awaitOffer();
                                    // Acknowledged, then never completed: the relay died mid-call.
                                    intents.ack("t1", offer.token());
                                    sleep(200);
                                    intents.expireDeadlines();
                                });

        ToolCallResult result = call(new ToolCall("0#1", "charge", Map.of()));

        relay.join(5000);
        assertThat(result.status()).isEqualTo(ToolCallStatus.TIMEOUT);
        assertThat(result.error()).contains("outcome is unknown");
    }

    @Test
    void reportsACallNoRelayTookAsUnavailable() throws Exception {
        intents =
                new InMemoryToolIntents(
                        store::holdsClaim,
                        signals,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(100));
        provider =
                new McpToolProvider(
                        catalogs, intents, signals, Duration.ofSeconds(5), Duration.ofMillis(100));
        Thread.ofVirtual()
                .start(
                        () -> {
                            sleep(200);
                            intents.expireDeadlines();
                        });

        ToolCallResult result = call(new ToolCall("0#1", "charge", Map.of()));

        assertThat(result.status()).isEqualTo(ToolCallStatus.UNAVAILABLE);
        assertThat(result.error()).contains("did not run");
    }

    @Test
    void refusesACallMadeOutsideARun() {
        assertThatThrownBy(() -> provider.call(new ToolCall("0#1", "charge", Map.of()), Map.of()))
                .isInstanceOf(RunPreemptedException.class);
    }

    private ToolCallResult call(ToolCall call) throws Exception {
        RunHandle run = registry.register(claim);
        try {
            return RunContext.runAs(run, () -> provider.call(call, Map.of()));
        } finally {
            registry.unregister(run);
        }
    }

    /// A relay that takes the next offer, acknowledges it and completes it with `outcome`.
    private Thread relay(String outcome) {
        return Thread.ofVirtual()
                .start(
                        () -> {
                            ToolIntents.Offer offer = awaitOffer();
                            assertThat(intents.ack("t1", offer.token()))
                                    .isEqualTo(ToolIntents.Ack.STARTED);
                            String status = outcome.contains("\"error\"") ? "FAILURE" : "SUCCESS";
                            intents.complete(
                                    "t1",
                                    offer.token(),
                                    new ToolIntents.Settlement(status, outcome));
                        });
    }

    private ToolIntents.Offer awaitOffer() {
        AtomicReference<ToolIntents.Offer> taken = new AtomicReference<>();
        for (int i = 0; i < 500 && taken.get() == null; i++) {
            List<ToolIntents.Offer> offers = intents.offer("t1", 1);
            if (!offers.isEmpty()) {
                taken.set(offers.getFirst());
            } else {
                sleep(10);
            }
        }
        return taken.get();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
