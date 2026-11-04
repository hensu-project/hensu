package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// The audit trail as a recovered run sees it: the replay reports its predecessor's calls
/// again, and each must still be recorded once and read back under its call id.
class JdbcToolAuditRepositoryTest extends JdbcRepositoryTestBase {

    private JdbcToolAuditRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("DELETE FROM runtime.tool_audit WHERE tenant_id = ?")) {
            ps.setString(1, TENANT);
            ps.executeUpdate();
        }
        repository = new JdbcToolAuditRepository(dataSource, objectMapper);
    }

    @Test
    void aReplayedCallIsRecordedOnceAndReadBackUnderItsCallId() {
        ToolAuditEntry entry =
                new ToolAuditEntry(
                        "exec-1",
                        "process",
                        "writer",
                        "read_file",
                        "SUCCESS",
                        null,
                        12,
                        Instant.now().truncatedTo(ChronoUnit.MICROS),
                        List.of(),
                        Map.of("path", "/data.txt"),
                        "file contents",
                        "exec-1/0#1");

        repository.record(TENANT, entry);
        repository.record(TENANT, entry);

        assertThat(repository.findByExecution(TENANT, "exec-1")).containsExactly(entry);
    }
}
