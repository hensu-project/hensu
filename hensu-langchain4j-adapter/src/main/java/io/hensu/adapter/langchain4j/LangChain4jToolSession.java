package io.hensu.adapter.langchain4j;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.agent.ToolSession;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolDefinition;
import java.util.*;
import java.util.Locale;
import java.util.logging.Logger;

/// LangChain4j implementation of {@link ToolSession}.
///
/// Manages a session-private message list for tool-call rounds. At session close,
/// appends only the final (prompt, answer) pair to the agent's shared history
/// under its lock – preventing cross-branch bleed of intermediate tool turns.
class LangChain4jToolSession implements ToolSession {

    private static final Logger logger = Logger.getLogger(LangChain4jToolSession.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LangChain4jAgent agent;
    private final ChatModel model;
    private final AgentConfig config;
    private final List<ChatMessage> sessionMessages;
    private final List<ToolSpecification> toolSpecs;
    private final Deque<ToolExecutionRequest> pendingQueue = new ArrayDeque<>();
    private ToolExecutionRequest lastDispatchedRequest;
    private UserMessage originalUserMessage;
    private AiMessage lastAiMessage;

    LangChain4jToolSession(
            LangChain4jAgent agent,
            ChatModel model,
            AgentConfig config,
            String prompt,
            Map<String, Object> context,
            List<ToolDefinition> tools) {
        this.agent = agent;
        this.model = model;
        this.config = config;
        this.sessionMessages = new ArrayList<>();
        this.toolSpecs = tools.stream().map(LangChain4jToolSession::toToolSpec).toList();

        buildInitialMessages(prompt, context);
    }

    @Override
    public AgentResponse start() {
        ChatRequest request =
                ChatRequest.builder()
                        .messages(sessionMessages)
                        .toolSpecifications(toolSpecs)
                        .build();
        ChatResponse chatResponse = model.chat(request);
        return processResponse(chatResponse);
    }

    @Override
    public AgentResponse submit(ToolCallResult result) {
        // Add the tool execution result for the last dispatched request
        if (lastDispatchedRequest != null) {
            sessionMessages.add(
                    ToolExecutionResultMessage.from(lastDispatchedRequest, result.asText()));
            lastDispatchedRequest = null;
        }

        // If more tool calls remain in this round, dispatch the next one
        if (!pendingQueue.isEmpty()) {
            ToolExecutionRequest next = pendingQueue.poll();
            lastDispatchedRequest = next;
            return toToolRequest(next);
        }

        // All tool calls from this round answered — re-call the model
        ChatRequest request =
                ChatRequest.builder()
                        .messages(sessionMessages)
                        .toolSpecifications(toolSpecs)
                        .build();
        ChatResponse chatResponse = model.chat(request);
        return processResponse(chatResponse);
    }

    @Override
    public void compact() {
        if (sessionMessages.size() <= 3) return;

        // Retain: system message (if present), original user message, last AI message
        List<ChatMessage> compacted = new ArrayList<>();
        for (ChatMessage msg : sessionMessages) {
            if (msg instanceof SystemMessage) {
                compacted.add(msg);
                break;
            }
        }
        if (originalUserMessage != null) {
            compacted.add(originalUserMessage);
        }
        if (lastAiMessage != null) {
            compacted.add(lastAiMessage);
        }
        sessionMessages.clear();
        sessionMessages.addAll(compacted);
        pendingQueue.clear();
    }

    @Override
    public void close() {
        if (lastAiMessage != null && lastAiMessage.text() != null && originalUserMessage != null) {
            agent.appendToHistory(originalUserMessage, lastAiMessage);
        }
    }

    private AgentResponse processResponse(ChatResponse chatResponse) {
        AiMessage aiMessage = chatResponse.aiMessage();
        lastAiMessage = aiMessage;
        sessionMessages.add(aiMessage);

        if (aiMessage.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            pendingQueue.clear();
            pendingQueue.addAll(requests);

            ToolExecutionRequest first = pendingQueue.poll();
            lastDispatchedRequest = first;
            return toToolRequest(first);
        }

        String text = aiMessage.text();
        if (text == null) text = "";
        return AgentResponse.TextResponse.of(text);
    }

    private AgentResponse.ToolRequest toToolRequest(ToolExecutionRequest request) {
        Map<String, Object> arguments = parseArguments(request.arguments());
        return AgentResponse.ToolRequest.of(request.name(), arguments);
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(argumentsJson, new TypeReference<>() {});
        } catch (Exception e) {
            logger.warning("Failed to parse tool arguments: " + e.getMessage());
            return Map.of("_raw", argumentsJson);
        }
    }

    private void buildInitialMessages(String prompt, Map<String, Object> context) {
        String modelName = config.getModel();
        boolean isGemini = modelName.startsWith("gemini") || modelName.startsWith("gemma");

        if (!config.getRole().isEmpty()) {
            String systemContent =
                    buildSystemPrompt(config.getRole(), config.getInstructions(), context);
            if (isGemini) {
                prompt = systemContent + prompt;
            } else {
                sessionMessages.add(SystemMessage.from(systemContent));
            }
        }

        if (config.isMaintainContext()) {
            sessionMessages.addAll(agent.getHistorySnapshot());
        }

        originalUserMessage = UserMessage.from(prompt);
        sessionMessages.add(originalUserMessage);
    }

    private String buildSystemPrompt(
            String role, String instructions, Map<String, Object> context) {
        var sb = new StringBuilder();
        sb.append("You are a ").append(role).append(".\n\n");
        if (instructions != null && !instructions.isEmpty()) {
            sb.append(instructions).append("\n\n");
        }
        if (context != null && !context.isEmpty()) {
            sb.append("Context information:\n");
            context.forEach(
                    (key, value) -> {
                        if (!key.startsWith("_")
                                && !key.equals("retry_attempt")
                                && !key.equals("backtrack_reason")
                                && !key.equals("loop_iteration")) {
                            sb.append("- ").append(key).append(": ").append(value).append("\n");
                        }
                    });
        }
        return sb.toString();
    }

    /// Builds the specification the model sees for one tool.
    ///
    /// A tool that published a JSON Schema is offered that schema, because the
    /// flat {@link ToolDefinition#parameters()} list cannot express an
    /// enumeration, an array's item type, or a nested object, and a model that
    /// never sees the constraint cannot respect it. A tool declared locally
    /// published nothing, so its schema is rebuilt from the parameter list.
    static ToolSpecification toToolSpec(ToolDefinition tool) {
        JsonObjectSchema paramSchema =
                tool.rawSchema() != null ? fromRawSchema(tool.rawSchema()) : fromParameters(tool);

        return ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(paramSchema)
                .build();
    }

    private static JsonObjectSchema fromParameters(ToolDefinition tool) {
        Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (var param : tool.parameters()) {
            properties.put(param.name(), toSchemaElement(param.type()));
            if (param.required()) {
                required.add(param.name());
            }
        }

        return JsonObjectSchema.builder().addProperties(properties).required(required).build();
    }

    /// Translates a published JSON Schema object into LangChain4j's model.
    ///
    /// Stays on the tree model throughout – no reflective binding, so the
    /// native image is unaffected. Keywords LangChain4j has no element for are
    /// dropped rather than approximated, which is the same thing the provider
    /// would do with them.
    private static JsonObjectSchema fromRawSchema(Map<String, Object> schema) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (schema.get("description") instanceof String description) {
            builder.description(description);
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            Map<String, JsonSchemaElement> translated = new LinkedHashMap<>();
            properties.forEach(
                    (name, value) -> {
                        if (name instanceof String key && value instanceof Map<?, ?> property) {
                            translated.put(key, toSchemaElement(property));
                        }
                    });
            builder.addProperties(translated);
        }
        builder.required(stringsOf(schema.get("required")));
        if (schema.get("additionalProperties") instanceof Boolean additional) {
            builder.additionalProperties(additional);
        }
        return builder.build();
    }

    private static JsonSchemaElement toSchemaElement(Map<?, ?> property) {
        String description = property.get("description") instanceof String text ? text : null;

        List<String> enumValues = stringsOf(property.get("enum"));
        if (!enumValues.isEmpty()) {
            return JsonEnumSchema.builder().enumValues(enumValues).description(description).build();
        }

        return switch (typeOf(property).toLowerCase(Locale.ROOT)) {
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "integer", "int" -> JsonIntegerSchema.builder().description(description).build();
            case "boolean", "bool" -> JsonBooleanSchema.builder().description(description).build();
            case "array" ->
                    JsonArraySchema.builder()
                            .items(
                                    property.get("items") instanceof Map<?, ?> items
                                            ? toSchemaElement(items)
                                            : JsonStringSchema.builder().build())
                            .description(description)
                            .build();
            case "object" -> nestedObject(property, description);
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    /// Reads a property's type, which JSON Schema allows to be a list.
    ///
    /// A list such as `["integer", "null"]` is how a schema says "nullable
    /// integer". The model is told the first non-null member, because a tool
    /// spec has no union to offer and falling back to a string would invite a
    /// quoted number the server then refuses.
    private static String typeOf(Map<?, ?> property) {
        return switch (property.get("type")) {
            case String named -> named;
            case List<?> named ->
                    named.stream()
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .filter(member -> !member.equals("null"))
                            .findFirst()
                            .orElse("string");
            case null, default -> "string";
        };
    }

    private static JsonObjectSchema nestedObject(Map<?, ?> property, String description) {
        JsonObjectSchema.Builder nested = JsonObjectSchema.builder().description(description);
        if (property.get("properties") instanceof Map<?, ?> properties) {
            Map<String, JsonSchemaElement> translated = new LinkedHashMap<>();
            properties.forEach(
                    (name, value) -> {
                        if (name instanceof String key && value instanceof Map<?, ?> child) {
                            translated.put(key, toSchemaElement(child));
                        }
                    });
            nested.addProperties(translated);
        }
        nested.required(stringsOf(property.get("required")));
        return nested.build();
    }

    private static List<String> stringsOf(Object value) {
        if (!(value instanceof List<?> listed)) {
            return List.of();
        }
        return listed.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static JsonSchemaElement toSchemaElement(String type) {
        return switch (type.toLowerCase()) {
            case "string" -> JsonStringSchema.builder().build();
            case "number" -> JsonNumberSchema.builder().build();
            case "integer", "int" -> JsonIntegerSchema.builder().build();
            case "boolean", "bool" -> JsonBooleanSchema.builder().build();
            default -> JsonStringSchema.builder().build();
        };
    }
}
