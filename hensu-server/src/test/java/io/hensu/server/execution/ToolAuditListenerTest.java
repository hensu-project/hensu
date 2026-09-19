package io.hensu.server.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolResultEvent;
import io.hensu.server.persistence.InMemoryToolAuditRepository;
import io.hensu.server.persistence.ToolAuditEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolAuditListenerTest {

    private static final String TENANT = "tenant-1";
    private static final String EXECUTION = "exec-1";

    @Test
    void shouldLeaveOneRowPerOutcomeIncludingTheOnesThatNeverRan() {
        InMemoryToolAuditRepository repository = new InMemoryToolAuditRepository();
        ToolAuditListener listener = new ToolAuditListener(repository, TENANT, EXECUTION);

        settle(listener, "run-tests", ToolCallResult.success("run-tests", "ok", 0));
        settle(
                listener,
                "deploy",
                ToolCallResult.of(
                                "deploy",
                                ToolCallStatus.DENIED,
                                null,
                                "a reviewer refused this call",
                                null)
                        .withArgv(List.of("/usr/bin/deploy", "prod")));
        settle(
                listener,
                "teleport",
                ToolCallResult.of(
                        "teleport", ToolCallStatus.UNKNOWN_TOOL, null, "no such tool", null));

        List<ToolAuditEntry> rows = repository.findByExecution(TENANT, EXECUTION);

        // A denial that left no row would make "the run did nothing" and "the run was
        // stopped from doing something" look identical afterwards.
        assertThat(rows)
                .extracting(ToolAuditEntry::status)
                .containsExactly("SUCCESS", "DENIED", "UNKNOWN_TOOL");
        assertThat(rows.get(1).argv()).containsExactly("/usr/bin/deploy", "prod");
    }

    @Test
    void shouldCarryTheRedactedArgumentsFromTheCallEventOntoTheRow() {
        InMemoryToolAuditRepository repository = new InMemoryToolAuditRepository();
        ToolAuditListener listener = new ToolAuditListener(repository, TENANT, EXECUTION);

        listener.onToolCall(
                ToolCallEvent.now(
                        "call-1",
                        "publish",
                        "releaser",
                        "deploy",
                        Map.of("environment", "prod", "token", ToolCallEvent.REDACTED)));
        listener.onToolResult(
                ToolResultEvent.now(
                        "call-1",
                        "publish",
                        "releaser",
                        ToolCallResult.success("deploy", "done", 0),
                        12L));

        ToolAuditEntry row = repository.findByExecution(TENANT, EXECUTION).getFirst();

        assertThat(row.arguments())
                .containsEntry("environment", "prod")
                .containsEntry("token", ToolCallEvent.REDACTED);
    }

    @Test
    void shouldStillRecordAResultWhoseCallEventNeverArrived() {
        InMemoryToolAuditRepository repository = new InMemoryToolAuditRepository();
        ToolAuditListener listener = new ToolAuditListener(repository, TENANT, EXECUTION);

        listener.onToolResult(
                ToolResultEvent.now(
                        "orphan",
                        "publish",
                        "releaser",
                        ToolCallResult.success("deploy", "done", 0),
                        5L));

        // Losing the arguments is a gap in one field; losing the row is a gap in the
        // record of what the run actually did.
        assertThat(repository.findByExecution(TENANT, EXECUTION)).hasSize(1);
    }

    @Test
    void shouldKeepConcurrentBranchesApartRatherThanPairingAcrossThem() {
        InMemoryToolAuditRepository repository = new InMemoryToolAuditRepository();
        ToolAuditListener listener = new ToolAuditListener(repository, TENANT, EXECUTION);

        listener.onToolCall(
                ToolCallEvent.now("a", "branch-a", "agent", "probe", Map.of("id", "a")));
        listener.onToolCall(
                ToolCallEvent.now("b", "branch-b", "agent", "probe", Map.of("id", "b")));
        listener.onToolResult(
                ToolResultEvent.now(
                        "b", "branch-b", "agent", ToolCallResult.success("probe", "b", 0), 1L));
        listener.onToolResult(
                ToolResultEvent.now(
                        "a", "branch-a", "agent", ToolCallResult.success("probe", "a", 0), 1L));

        // A fan-out runs the same tool in two branches at once. Pairing by tool name alone
        // would attribute one branch's arguments to the other's result.
        List<ToolAuditEntry> rows = repository.findByExecution(TENANT, EXECUTION);
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().arguments()).containsEntry("id", "b");
        assertThat(rows.get(1).arguments()).containsEntry("id", "a");
    }

    @Test
    void shouldKeepTwoCallsApartWhenNodeAndToolNameAreIdentical() {
        InMemoryToolAuditRepository repository = new InMemoryToolAuditRepository();
        ToolAuditListener listener = new ToolAuditListener(repository, TENANT, EXECUTION);

        // Nothing in today's graph shapes delivers two concurrent calls with the same node
        // and tool name, so this is not a bug the engine can currently reach. It is the
        // assumption the old keying rested on, held by the graph rather than by this
        // listener: interleave two such calls and the second evicted the first, reporting
        // its arguments twice and losing the first call's.
        listener.onToolCall(ToolCallEvent.now("first", "audit", "agent", "probe", Map.of("id", 1)));
        listener.onToolCall(
                ToolCallEvent.now("second", "audit", "agent", "probe", Map.of("id", 2)));
        listener.onToolResult(
                ToolResultEvent.now(
                        "first", "audit", "agent", ToolCallResult.success("probe", "1", 0), 1L));
        listener.onToolResult(
                ToolResultEvent.now(
                        "second", "audit", "agent", ToolCallResult.success("probe", "2", 0), 1L));

        List<ToolAuditEntry> rows = repository.findByExecution(TENANT, EXECUTION);
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().arguments()).containsEntry("id", 1);
        assertThat(rows.get(1).arguments()).containsEntry("id", 2);
    }

    private static void settle(ToolAuditListener listener, String tool, ToolCallResult result) {
        String callId = "call-" + tool;
        listener.onToolCall(ToolCallEvent.now(callId, "publish", "releaser", tool, Map.of()));
        listener.onToolResult(ToolResultEvent.now(callId, "publish", "releaser", result, 7L));
    }
}
