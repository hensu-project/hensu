package io.hensu.adapter.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/// Encodes the messages of a tool session as journal turns, and back.
///
/// A recovered session rebuilds its conversation from these strings instead of asking
/// the model again, so the encoding keeps everything the next request depends on: the
/// text of every message, each tool request's provider id, name and arguments, and an
/// assistant message's thinking and scalar attributes – a provider that signs its
/// thinking blocks needs the signature back on the next turn. Stays on Jackson's tree
/// model throughout, so the native image needs no reflection metadata.
///
/// @implNote Stateless and thread-safe.
final class ChatMessageCodec {

    private static final Logger logger = Logger.getLogger(ChatMessageCodec.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatMessageCodec() {}

    /// Encodes the opening conversation of a session.
    static String encode(List<ChatMessage> messages) {
        ArrayNode array = MAPPER.createArrayNode();
        messages.forEach(message -> array.add(node(message)));
        return array.toString();
    }

    /// Decodes an opening conversation.
    static List<ChatMessage> decode(String encoded) {
        List<ChatMessage> messages = new ArrayList<>();
        for (JsonNode node : read(encoded)) {
            messages.add(message(node));
        }
        return messages;
    }

    /// Encodes one answer of the model.
    static String encode(AiMessage message) {
        return node(message).toString();
    }

    /// Decodes one answer of the model.
    static AiMessage decodeAi(String encoded) {
        return (AiMessage) message(read(encoded));
    }

    private static ObjectNode node(ChatMessage message) {
        ObjectNode node = MAPPER.createObjectNode();
        switch (message) {
            case SystemMessage system -> node.put("type", "system").put("text", system.text());
            case UserMessage user -> {
                if (!user.hasSingleText()) {
                    throw new IllegalArgumentException(
                            "A tool session journals text user messages only");
                }
                node.put("type", "user").put("text", user.singleText());
                if (user.name() != null) {
                    node.put("name", user.name());
                }
            }
            case AiMessage ai -> {
                node.put("type", "ai");
                if (ai.text() != null) {
                    node.put("text", ai.text());
                }
                if (ai.thinking() != null) {
                    node.put("thinking", ai.thinking());
                }
                if (ai.hasToolExecutionRequests()) {
                    ArrayNode requests = node.putArray("toolCalls");
                    for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                        ObjectNode r = requests.addObject();
                        r.put("id", request.id());
                        r.put("name", request.name());
                        r.put("arguments", request.arguments());
                    }
                }
                ObjectNode attributes = scalarAttributes(ai.attributes());
                if (!attributes.isEmpty()) {
                    node.set("attributes", attributes);
                }
            }
            case ToolExecutionResultMessage result ->
                    node.put("type", "tool")
                            .put("id", result.id())
                            .put("name", result.toolName())
                            .put("text", result.text());
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported message in a tool session: " + message.type());
        }
        return node;
    }

    private static ChatMessage message(JsonNode node) {
        return switch (node.path("type").asText()) {
            case "system" -> SystemMessage.from(requiredText(node));
            case "user" ->
                    node.hasNonNull("name")
                            ? UserMessage.from(node.path("name").asText(), requiredText(node))
                            : UserMessage.from(requiredText(node));
            case "ai" -> {
                AiMessage.Builder builder =
                        AiMessage.builder()
                                .text(text(node, "text"))
                                .thinking(text(node, "thinking"));
                if (node.has("toolCalls")) {
                    List<ToolExecutionRequest> requests = new ArrayList<>();
                    for (JsonNode r : node.path("toolCalls")) {
                        requests.add(
                                ToolExecutionRequest.builder()
                                        .id(text(r, "id"))
                                        .name(text(r, "name"))
                                        .arguments(text(r, "arguments"))
                                        .build());
                    }
                    builder.toolExecutionRequests(requests);
                }
                if (node.has("attributes")) {
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    node.path("attributes")
                            .properties()
                            .forEach(e -> attributes.put(e.getKey(), scalar(e.getValue())));
                    builder.attributes(attributes);
                }
                yield builder.build();
            }
            case "tool" ->
                    ToolExecutionResultMessage.from(
                            text(node, "id"), text(node, "name"), requiredText(node));
            default ->
                    throw new IllegalArgumentException(
                            "Unknown journaled message type: " + node.path("type").asText());
        };
    }

    private static ObjectNode scalarAttributes(Map<String, Object> attributes) {
        ObjectNode node = MAPPER.createObjectNode();
        if (attributes == null) {
            return node;
        }
        attributes.forEach(
                (key, value) -> {
                    switch (value) {
                        case String s -> node.put(key, s);
                        case Boolean b -> node.put(key, b);
                        case Integer i -> node.put(key, i);
                        case Long l -> node.put(key, l);
                        case Double d -> node.put(key, d);
                        case null -> node.putNull(key);
                        default ->
                                logger.warning(
                                        "Assistant message attribute '"
                                                + key
                                                + "' is not a scalar and is not journaled");
                    }
                });
        return node;
    }

    private static Object scalar(JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isInt()) return value.asInt();
        if (value.isLong()) return value.asLong();
        if (value.isNumber()) return value.asDouble();
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /// Reads the text this codec writes on every system, user and tool message, so its
    /// absence means the journal holds a turn this codec did not write.
    private static String requiredText(JsonNode node) {
        String value = text(node, "text");
        if (value == null) {
            throw new IllegalStateException(
                    "Unreadable journaled turn: a '"
                            + node.path("type").asText()
                            + "' message has no 'text'");
        }
        return value;
    }

    private static JsonNode read(String encoded) {
        try {
            return MAPPER.readTree(encoded);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable journaled turn: " + e.getMessage(), e);
        }
    }
}
