package io.hensu.server.multinode;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// The checker's verdicts on histories with a known violation. A checker that never flags
/// would pass every fault-injection run, so each safety check is shown one effect it must
/// catch beside one it must let through.
class CheckerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /// Execution `x`: n1 claims epoch 1 at 1 s; the claim lapses and n2 claims epoch 2 at 8 s.
    private static final List<Map<String, Object>> CLAIMS =
            List.of(
                    audit(1_000, "INSERT", "x", null, row(1, "n1")),
                    audit(8_000, "UPDATE", "x", row(1, "n1"), row(2, "n2")));

    @Test
    void s3_flagsAnEffectOfAnIntentWrittenUnderASupersededEpoch() {
        Checker.Report report =
                check(
                        List.of(executed("x/0#1", "a"), executed("x/0#2", "b")),
                        // The old run wrote 0#1 after epoch 2 was claimed; 0#2 is the new run's.
                        List.of(intent("0#1", 1, 9_000), intent("0#2", 2, 9_000)));

        assertThat(report.findings().get("S3"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("x/0#1"));
        assertThat(report.safetyViolations()).isOne();
    }

    @Test
    void s3a_flagsALogicalCallTheRelayExecutedTwice() {
        Checker.Report report =
                check(
                        List.of(
                                executed("x/0#1", "a"),
                                executed("x/0#1", "a"),
                                executed("x/1#1", "b")),
                        List.of(intent("0#1", 1, 2_000), intent("1#1", 1, 3_000)));

        assertThat(report.findings().get("S3a"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.detail()).startsWith("a executed 2"));
        assertThat(report.safetyViolations()).isOne();
    }

    @Test
    void s5_flagsATakeoverWithinALeaseOfTheHoldersLastRenewal() {
        long lease = FaultTestConfig.CLAIM_TTL.toMillis();
        long renewed = 1_000 + lease;
        JsonNode renewedX = row("RUNNING", 1, "n1", "renewed");
        JsonNode renewedY = row("RUNNING", 1, "n1", "renewed");
        List<Map<String, Object>> audit =
                List.of(
                        // x: n2 takes a live claim a lease and a half after it was taken but
                        // half a lease after its renewal, so only an age measured from the
                        // renewal flags it.
                        audit(1_000, "INSERT", "x", null, row(1, "n1")),
                        audit(renewed, "UPDATE", "x", row(1, "n1"), renewedX),
                        audit(renewed + lease / 2, "UPDATE", "x", renewedX, row(2, "n2")),
                        // y: n1 released the claim, so n2 may pick it up at once.
                        audit(1_000, "INSERT", "y", null, row(1, "n1")),
                        audit(renewed, "UPDATE", "y", row(1, "n1"), renewedY),
                        audit(
                                renewed + 1_000,
                                "UPDATE",
                                "y",
                                renewedY,
                                row("RUNNABLE", 1, null, null)),
                        audit(
                                renewed + 2_000,
                                "UPDATE",
                                "y",
                                row("RUNNABLE", 1, null, null),
                                row(2, "n2")));

        Checker.Report report = check(audit, List.of(), List.of());

        assertThat(report.findings().get("S5"))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.execution()).isEqualTo("x");
                            assertThat(finding.detail()).contains(lease / 2 + " ms earlier");
                        });
        assertThat(report.safetyViolations()).isOne();
    }

    private static Checker.Report check(
            List<History.Event> executed, List<Map<String, Object>> intents) {
        return check(CLAIMS, executed, intents);
    }

    private static Checker.Report check(
            List<Map<String, Object>> audit,
            List<History.Event> executed,
            List<Map<String, Object>> intents) {
        return new Checker(new Checker.Observed(executed, audit, List.of(), List.of(), intents))
                .check();
    }

    /// The relay running the call `key` that workflow node `node` of execution `x` issued.
    private static History.Event executed(String key, String node) {
        return new History.Event(
                10_000,
                "executed",
                "t1",
                "n2",
                "x",
                "token-" + key,
                key,
                "record",
                Map.of("execution", "x", "node", node),
                0,
                null);
    }

    private static Map<String, Object> intent(String callKey, long epoch, long createdAt) {
        Map<String, Object> intent = new HashMap<>();
        intent.put("tenant_id", "t1");
        intent.put("execution_id", "x");
        intent.put("call_key", callKey);
        intent.put("created_epoch", epoch);
        intent.put("created_at", createdAt);
        return intent;
    }

    private static Map<String, Object> audit(
            long at, String op, String execution, JsonNode before, JsonNode after) {
        Map<String, Object> row = new HashMap<>();
        row.put("at", at);
        row.put("op", op);
        row.put("tenant_id", "t1");
        row.put("execution_id", execution);
        row.put("old_row", before);
        row.put("new_row", after);
        return row;
    }

    private static JsonNode row(long epoch, String owner) {
        return row("RUNNING", epoch, owner, "epoch-" + epoch);
    }

    private static JsonNode row(String runState, long epoch, String owner, String expiresAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("run_state", runState);
        row.put("claim_epoch", epoch);
        row.put("claim_owner", owner);
        row.put("claim_expires_at", expiresAt);
        return JSON.valueToTree(row);
    }
}
