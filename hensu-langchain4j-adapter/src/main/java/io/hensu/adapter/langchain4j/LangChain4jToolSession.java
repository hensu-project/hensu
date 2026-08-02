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
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
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

    private static ToolSpecification toToolSpec(ToolDefinition tool) {
        Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (var param : tool.parameters()) {
            properties.put(param.name(), toSchemaElement(param.type()));
            if (param.required()) {
                required.add(param.name());
            }
        }

        JsonObjectSchema paramSchema =
                JsonObjectSchema.builder().addProperties(properties).required(required).build();

        return ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(paramSchema)
                .build();
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
