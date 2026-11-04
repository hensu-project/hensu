package io.hensu.server.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hensu.server.streaming.ClusterEventBus;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/// Carries execution events between nodes on the `hensu_events` channel.
///
/// The payload is `executionId TAB type TAB json`, or `executionId TAB complete`. A
/// notification payload must stay under 8000 bytes, so an event that does not fit has its
/// longest strings cut – the same policy tool output already follows – and is marked
/// `"truncated": true`; the full state is always available from the execution resource.
///
/// @implNote Thread-safe.
public final class PgEventBus implements ClusterEventBus {

    private static final Logger LOG = Logger.getLogger(PgEventBus.class);
    private static final int MAX_PAYLOAD_BYTES = 7500;
    private static final String MARKER = "… [truncated]";

    private final JdbcSupport jdbc;
    private final ObjectMapper mapper;
    private final ExecutionEventBroadcaster broadcaster;

    public PgEventBus(
            DataSource dataSource, ObjectMapper mapper, ExecutionEventBroadcaster broadcaster) {
        this.jdbc =
                new JdbcSupport(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster must not be null");
    }

    @Override
    public void publish(String executionId, ExecutionEvent event) {
        ObjectNode json = mapper.valueToTree(event);
        String body = fit(executionId, event.getClass().getSimpleName(), json);
        notify(executionId + "\t" + event.getClass().getSimpleName() + "\t" + body);
    }

    @Override
    public void complete(String executionId) {
        notify(executionId + "\tcomplete");
    }

    /// Delivers a notification received on `hensu_events` to this node's subscribers.
    ///
    /// @param payload the notification payload, not null
    public void receive(String payload) {
        String[] parts = payload.split("\t", 3);
        if (parts.length == 2 && parts[1].equals("complete")) {
            broadcaster.completeLocally(parts[0]);
            return;
        }
        if (parts.length != 3) {
            return;
        }
        try {
            ObjectNode json = (ObjectNode) mapper.readTree(parts[2]);
            json.remove("type");
            json.remove("truncated");
            Class<? extends ExecutionEvent> type =
                    switch (parts[1]) {
                        case "ExecutionStarted" -> ExecutionEvent.ExecutionStarted.class;
                        case "ExecutionPaused" -> ExecutionEvent.ExecutionPaused.class;
                        case "ExecutionCompleted" -> ExecutionEvent.ExecutionCompleted.class;
                        case "ExecutionError" -> ExecutionEvent.ExecutionError.class;
                        case "ToolInvoked" -> ExecutionEvent.ToolInvoked.class;
                        case "ToolSettled" -> ExecutionEvent.ToolSettled.class;
                        default -> null;
                    };
            if (type != null) {
                broadcaster.deliver(parts[0], mapper.treeToValue(json, type));
            }
        } catch (Exception e) {
            LOG.debugv("Dropped an undecodable event notification: {0}", e.getMessage());
        }
    }

    private void notify(String payload) {
        jdbc.queryOne(
                "SELECT pg_notify('hensu_events', ?) IS NULL AS sent",
                ps -> ps.setString(1, payload),
                _ -> true,
                "Failed to publish an execution event");
    }

    /// Cuts the longest strings until the payload fits a notification.
    private String fit(String executionId, String type, ObjectNode json) {
        int overhead = (executionId + type).getBytes(StandardCharsets.UTF_8).length + 2;
        String body = json.toString();
        while (body.getBytes(StandardCharsets.UTF_8).length + overhead > MAX_PAYLOAD_BYTES) {
            Longest longest = new Longest();
            find(json, longest);
            if (longest.parent == null || longest.length <= 64) {
                // Nothing left worth cutting: send the event's identity alone.
                ObjectNode bare = mapper.createObjectNode();
                bare.put("executionId", executionId);
                bare.put("truncated", true);
                return bare.toString();
            }
            String value = longest.parent.get(longest.field).asText();
            longest.parent.put(longest.field, value.substring(0, value.length() / 2) + MARKER);
            json.put("truncated", true);
            body = json.toString();
        }
        return body;
    }

    private static final class Longest {
        ObjectNode parent;
        String field;
        int length;
    }

    private static void find(JsonNode node, Longest longest) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                JsonNode value = field.getValue();
                if (value.isTextual() && value.asText().length() > longest.length) {
                    longest.parent = object;
                    longest.field = field.getKey();
                    longest.length = value.asText().length();
                } else if (value.isContainerNode()) {
                    find(value, longest);
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> find(child, longest));
        }
    }
}
