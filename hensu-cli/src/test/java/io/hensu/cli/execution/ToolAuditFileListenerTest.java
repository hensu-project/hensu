package io.hensu.cli.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolResultEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Tests for the audit trail a CLI run leaves behind.
///
/// The file is the only record an unattended laptop run produces, so the properties that
/// matter are that every settled call reaches it and that each row carries the arguments
/// of the call it actually describes.
class ToolAuditFileListenerTest {

    @TempDir Path tempDir;

    @Test
    void shouldPairTwoCallsApartWhenNodeAndToolNameAreIdentical() throws IOException {
        Path file = tempDir.resolve("tool-audit.log");
        ToolAuditFileListener listener = new ToolAuditFileListener("exec-1", file);

        // Today's graph shapes never deliver two concurrent calls sharing a node and tool
        // name, so this interleaving is hardening rather than a reachable bug. It pins the
        // property the rows need: keyed by node and tool name, the second call evicted the
        // first, and one row reported the other's arguments.
        listener.onToolCall(ToolCallEvent.now("first", "audit", "agent", "probe", Map.of("id", 1)));
        listener.onToolCall(
                ToolCallEvent.now("second", "audit", "agent", "probe", Map.of("id", 2)));
        listener.onToolResult(
                ToolResultEvent.now(
                        "second", "audit", "agent", ToolCallResult.success("probe", "2", 0), 3L));
        listener.onToolResult(
                ToolResultEvent.now(
                        "first", "audit", "agent", ToolCallResult.success("probe", "1", 0), 4L));

        List<JsonNode> rows = rows(file);

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().get("call_id").asText()).isEqualTo("second");
        assertThat(rows.getFirst().get("arguments").get("id").asInt()).isEqualTo(2);
        assertThat(rows.get(1).get("call_id").asText()).isEqualTo("first");
        assertThat(rows.get(1).get("arguments").get("id").asInt()).isEqualTo(1);
    }

    @Test
    void shouldStillRecordAResultWhoseCallEventNeverArrived() throws IOException {
        Path file = tempDir.resolve("tool-audit.log");
        ToolAuditFileListener listener = new ToolAuditFileListener("exec-1", file);

        listener.onToolResult(
                ToolResultEvent.now(
                        "orphan", "audit", "agent", ToolCallResult.success("probe", "out", 0), 1L));

        // Losing the arguments is a gap in one field; losing the row is a gap in the
        // record of what the run did to the machine.
        List<JsonNode> rows = rows(file);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("arguments")).isEmpty();
    }

    private static List<JsonNode> rows(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(
                        line -> {
                            try {
                                return mapper.readTree(line);
                            } catch (IOException e) {
                                throw new IllegalStateException("unreadable audit row: " + line, e);
                            }
                        })
                .toList();
    }
}
