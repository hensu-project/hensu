package io.hensu.server.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/// The JSON forms of a snapshot: the columns of an execution row, and the single document
/// a nested flow's checkpoint is journaled as.
///
/// Both go through the server's configured mapper, whose mixins already describe the
/// engine's types for the native image; nothing here adds reflection.
final class SnapshotRows {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Integer>> RETRY_COUNTERS_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper mapper;

    SnapshotRows(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Failed to serialize to JSON", e);
        }
    }

    /// Reads the snapshot columns of an execution row.
    HensuSnapshot read(String executionId, ResultSet rs) throws SQLException {
        Map<String, Object> context = read(rs.getString("context"), MAP_TYPE);
        Map<String, Integer> retryCounters =
                read(rs.getString("retry_counters"), RETRY_COUNTERS_TYPE);
        ExecutionHistory history = read(rs.getString("history"), ExecutionHistory.class);
        ExecutionPhase phase = read(rs.getString("phase"), ExecutionPhase.class);
        OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
        // A HashMap: the snapshot copies it immutably, toState() needs a mutable copy back.
        return new HensuSnapshot(
                rs.getString("workflow_id"),
                executionId,
                rs.getString("current_node_id"),
                new HashMap<>(context),
                retryCounters,
                history,
                phase,
                created != null ? created.toInstant() : Instant.now(),
                rs.getString("checkpoint_reason"),
                rs.getLong("step"));
    }

    /// Encodes a nested flow's checkpoint as one document.
    String document(HensuSnapshot snapshot) {
        ObjectNode node = mapper.createObjectNode();
        node.put("workflowId", snapshot.workflowId());
        node.put("executionId", snapshot.executionId());
        node.put("currentNodeId", snapshot.currentNodeId());
        node.set("context", mapper.valueToTree(snapshot.context()));
        node.set("retryCounters", mapper.valueToTree(snapshot.retryCounters()));
        node.set("history", mapper.valueToTree(snapshot.history()));
        node.set("phase", mapper.valueToTree(snapshot.phase()));
        node.put("step", snapshot.step());
        return node.toString();
    }

    /// Decodes a document written by {@link #document}.
    HensuSnapshot fromDocument(String document) {
        try {
            JsonNode node = mapper.readTree(document);
            return new HensuSnapshot(
                    node.path("workflowId").asText(),
                    node.path("executionId").asText(),
                    node.path("currentNodeId").isNull()
                            ? null
                            : node.path("currentNodeId").asText(),
                    new HashMap<>(mapper.convertValue(node.path("context"), MAP_TYPE)),
                    mapper.convertValue(node.path("retryCounters"), RETRY_COUNTERS_TYPE),
                    mapper.treeToValue(node.path("history"), ExecutionHistory.class),
                    mapper.treeToValue(node.path("phase"), ExecutionPhase.class),
                    Instant.now(),
                    "checkpoint",
                    node.path("step").asLong());
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Unreadable flow checkpoint", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Failed to deserialize from JSON", e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Failed to deserialize from JSON", e);
        }
    }
}
