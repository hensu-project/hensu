package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.ReplayDivergenceException;
import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.tool.ToolCall;
import io.hensu.server.execution.Claim;
import io.hensu.server.mcp.ToolIntents.Ack;
import io.hensu.server.mcp.ToolIntents.Completion;
import io.hensu.server.mcp.ToolIntents.Intent;
import io.hensu.server.mcp.ToolIntents.Offer;
import io.hensu.server.mcp.ToolIntents.Settlement;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Tool intents at the level where delivery is decided. The headline property is S3a: an
/// intent is offered as often as it takes, but exactly one acknowledgement succeeds, so a
/// relay that acknowledges before executing runs it at most once.
class JdbcToolIntentsTest extends JdbcRepositoryTestBase {

    private static final Settlement OK = new Settlement("SUCCESS", "{\"result\":{\"content\":[]}}");

    private JdbcExecutionStore store;
    private JdbcToolIntents intents;
    private Claim claim;

    @BeforeEach
    void setUp() {
        deleteExecutions(TENANT);
        deleteExecutions(OTHER_TENANT);
        JdbcWorkflowRepository workflows = new JdbcWorkflowRepository(dataSource);
        workflows.deleteAllForTenant(TENANT);
        workflows.save(TENANT, buildWorkflow("wf"));
        store = new JdbcExecutionStore(dataSource, objectMapper, "node-a", Duration.ofSeconds(30));
        intents =
                new JdbcToolIntents(
                        dataSource,
                        objectMapper,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(5));
        claim =
                store.start(
                        TENANT,
                        new HensuSnapshot(
                                "wf",
                                "x",
                                "process",
                                new HashMap<>(),
                                Map.of(),
                                new ExecutionHistory(),
                                null,
                                Instant.now(),
                                "checkpoint",
                                0));
    }

    @Test
    void s3_aSupersededClaimCannotAddACall() {
        sql("UPDATE runtime.execution_states SET claim_expires_at = now() - interval '1 second'");
        store.pickUp(10);

        assertThatThrownBy(() -> intents.submit(claim, call(1)))
                .isInstanceOf(RunPreemptedException.class);
        assertThat(intents.offer(TENANT, 10)).isEmpty();
    }

    @Test
    void s7_aReplayFindsTheOriginalIntentAndADifferentCallUnderItsKeyIsADivergence() {
        Intent original = intents.submit(claim, call(1));

        assertThat(intents.submit(claim, call(1))).isEqualTo(original);
        assertThatThrownBy(() -> intents.submit(claim, call(2)))
                .isInstanceOf(ReplayDivergenceException.class);
        assertThat(intents.offer(TENANT, 10)).hasSize(1);
    }

    @Test
    void callsOfTwoExecutionsAtTheSamePositionReachTheRelayUnderDistinctKeys() {
        Claim other =
                store.start(
                        TENANT,
                        new HensuSnapshot(
                                "wf",
                                "y",
                                "process",
                                new HashMap<>(),
                                Map.of(),
                                new ExecutionHistory(),
                                null,
                                Instant.now(),
                                "checkpoint",
                                0));
        intents.submit(claim, call(1));
        intents.submit(other, call(1));

        // A relay de-duplicates on the key, so it must name the execution as well as the
        // position: both executions are at step 0, and both calls must run.
        assertThat(intents.offer(TENANT, 10))
                .extracting(Offer::key)
                .containsExactlyInAnyOrder("x/0#1", "y/0#1");
    }

    @Test
    void s3a_exactlyOneAcknowledgementSucceedsHoweverOftenTheIntentIsOffered() {
        intents.submit(claim, call(1));
        Offer first = intents.offer(TENANT, 10).getFirst();

        // The poll response carrying the first offer is lost; the offer expires and the
        // intent is offered again under a new token.
        sql("UPDATE runtime.tool_intents SET offer_expires_at = now() - interval '1 second'");
        intents.expireDeadlines();
        Offer second = intents.offer(TENANT, 10).getFirst();
        assertThat(second.token()).isNotEqualTo(first.token());

        assertThat(intents.ack(TENANT, first.token())).isEqualTo(Ack.NOT_OFFERED);
        assertThat(intents.ack(TENANT, second.token())).isEqualTo(Ack.STARTED);
        // A retry of the same acknowledgement – its reply was lost – is told to go ahead.
        assertThat(intents.ack(TENANT, second.token())).isEqualTo(Ack.STARTED);
        // A started intent is never offered again.
        sql("UPDATE runtime.tool_intents SET offer_expires_at = now() - interval '1 second'");
        intents.expireDeadlines();
        assertThat(intents.offer(TENANT, 10)).isEmpty();
    }

    @Test
    void s6_theFirstOutcomeWinsAndOnlyTheOwningTenantCanSupplyIt() {
        Intent intent = intents.submit(claim, call(1));
        Offer offer = intents.offer(TENANT, 10).getFirst();
        intents.ack(TENANT, offer.token());

        assertThat(intents.complete(OTHER_TENANT, offer.token(), OK)).isEqualTo(Completion.UNKNOWN);
        assertThat(intents.complete(TENANT, offer.token(), OK)).isEqualTo(Completion.SETTLED);
        assertThat(intents.complete(TENANT, offer.token(), new Settlement("FAILURE", "{}")))
                .isEqualTo(Completion.ALREADY_SETTLED);
        Settlement recorded = intents.settlement(intent).orElseThrow();
        assertThat(recorded.status()).isEqualTo("SUCCESS");
        assertThat(json(recorded.outcome())).isEqualTo(json(OK.outcome()));
    }

    @Test
    void anotherTenantSeesAndAcknowledgesNothing() {
        intents.submit(claim, call(1));

        assertThat(intents.offer(OTHER_TENANT, 10)).isEmpty();
        Offer offer = intents.offer(TENANT, 10).getFirst();
        assertThat(intents.ack(OTHER_TENANT, offer.token())).isEqualTo(Ack.NOT_OFFERED);
    }

    @Test
    void anAcknowledgedCallPastItsDeadlineSettlesAsOutcomeUnknown() {
        Intent intent = intents.submit(claim, call(1));
        Offer offer = intents.offer(TENANT, 10).getFirst();
        intents.ack(TENANT, offer.token());

        sql("UPDATE runtime.tool_intents SET deadline_at = now() - interval '1 second'");
        intents.expireDeadlines();

        assertThat(intents.settlement(intent))
                .get()
                .extracting(Settlement::status)
                .isEqualTo("TIMEOUT");
        // The relay's late answer is not recorded over the timeout.
        assertThat(intents.complete(TENANT, offer.token(), OK))
                .isEqualTo(Completion.ALREADY_SETTLED);
    }

    @Test
    void anIntentNoRelayTookSettlesAsUnavailable() {
        Intent intent = intents.submit(claim, call(1));

        sql("UPDATE runtime.tool_intents SET dispatch_deadline_at = now() - interval '1 second'");
        intents.expireDeadlines();

        assertThat(intents.settlement(intent))
                .get()
                .extracting(Settlement::status)
                .isEqualTo("UNAVAILABLE");
        assertThat(intents.offer(TENANT, 10)).isEmpty();
    }

    @Test
    void settlingClearsTheArgumentsButKeepsTheirDigest() {
        Intent intent = intents.submit(claim, call(1));
        Offer offer = intents.offer(TENANT, 10).getFirst();
        assertThat(offer.arguments()).containsEntry("n", 1);
        intents.ack(TENANT, offer.token());
        intents.complete(TENANT, offer.token(), OK);

        assertThat(queryString()).isNull();
        // The digest outlives the arguments, so a divergent replay is still caught.
        assertThatThrownBy(() -> intents.submit(claim, call(2)))
                .isInstanceOf(ReplayDivergenceException.class);
        assertThat(intents.submit(claim, call(1))).isEqualTo(intent);
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolCall call(int n) {
        return new ToolCall("0#1", "record", Map.of("n", n));
    }

    private static void sql(String statement) {
        try (Connection c = dataSource.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate(statement);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String queryString() {
        try (Connection c = dataSource.getConnection();
                var s = c.createStatement();
                var rs = s.executeQuery("SELECT arguments::text FROM runtime.tool_intents")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
