package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.tool.ToolCall;
import io.hensu.server.execution.Claim;
import io.hensu.server.mcp.ClusterSignals;
import io.hensu.server.mcp.ToolIntents.Intent;
import io.hensu.server.mcp.ToolIntents.Settlement;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// The in-memory intents follow a settled execution as the PostgreSQL settle statement
/// does, although the store tells them only after its lock is released.
class InMemoryToolIntentsTest {

    private final InMemoryToolIntents intents =
            new InMemoryToolIntents(
                    _ -> true,
                    new ClusterSignals(),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(60),
                    Duration.ofMinutes(5));

    @Test
    void aSettleThatArrivesAfterAResumeLeavesTheResumedCallsAlone() {
        Claim paused = new Claim("t", "x", 1);
        Claim resumed = new Claim("t", "x", 2);
        Intent before = settledCall(paused, "0#1");
        Intent after = settledCall(resumed, "1#1");

        intents.settled(paused, false);

        assertThat(intents.settlement(before)).contains(new Settlement("SUCCESS", null));
        assertThat(intents.settlement(after)).contains(new Settlement("SUCCESS", "{}"));
    }

    private Intent settledCall(Claim claim, String key) {
        Intent intent = intents.submit(claim, new ToolCall(key, "t", Map.of()));
        String token = intents.offer(claim.tenantId(), 1).getFirst().token();
        intents.ack(claim.tenantId(), token);
        intents.complete(claim.tenantId(), token, new Settlement("SUCCESS", "{}"));
        return intent;
    }
}
