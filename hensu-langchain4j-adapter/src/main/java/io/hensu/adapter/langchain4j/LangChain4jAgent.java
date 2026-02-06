package io.hensu.adapter.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/// LangChain4j implementation of {@link Agent}.
///
/// Wraps a {@link ChatModel} and manages optional conversation history for
/// multi-turn interactions. Builds system prompts from the agent's role,
/// instructions, and execution context.
///
/// Template resolution is handled by the engine ({@code StandardNodeExecutor})
/// before the prompt reaches this agent — the prompt arrives already resolved.
///
/// @implNote **Not thread-safe** due to mutable `conversationHistory`.
/// Each agent instance should be used by a single workflow execution at a time.
///
/// @see LangChain4jProvider for agent creation
/// @see Agent for the contract
public class LangChain4jAgent implements Agent {

    private static final Logger logger = Logger.getLogger(LangChain4jAgent.class.getName());

    private final String id;
    private final AgentConfig config;
    private final ChatModel model;
    private final List<ChatMessage> conversationHistory;

    /// Creates a new agent wrapping the given chat model.
    ///
    /// @param id unique agent identifier, not null
    /// @param config agent configuration (role, instructions, model params), not null
    /// @param model the LangChain4j chat model to delegate to, not null
    public LangChain4jAgent(String id, AgentConfig config, ChatModel model) {
        this.id = id;
        this.config = config;
        this.model = model;
        this.conversationHistory = new ArrayList<>();
    }

    /// Executes the prompt against the underlying chat model.
    ///
    /// Builds a message list from the agent's system prompt, conversation history
    /// (if `maintainContext` is enabled), and the user prompt. Returns the model's
    /// response with token usage metadata.
    ///
    /// @param prompt the resolved prompt text, not null
    /// @param context execution context variables, not null
    /// @return text response with metadata on success, error response on failure; never null
    @Override
    public AgentResponse execute(String prompt, Map<String, Object> context) {
        Instant startTime = Instant.now();

        try {
            logger.fine("Agent '" + id + "' executing with role: " + config.getRole());

            List<ChatMessage> messages = buildMessages(prompt, context);
            ChatResponse response = model.chat(messages);

            if (response == null) {
                return AgentResponse.Error.of("No response from model");
            }

            AiMessage aiMessage = response.aiMessage();
            String output = aiMessage.text();

            if (config.isMaintainContext()) {
                conversationHistory.add(UserMessage.from(prompt));
                conversationHistory.add(aiMessage);
            }

            Map<String, Object> metadata = buildMetadata(response, startTime);
            logger.fine("Agent '" + id + "' completed successfully");

            return AgentResponse.TextResponse.of(output, metadata);

        } catch (Exception e) {
            logger.severe("Agent '" + id + "' execution failed: " + e.getMessage());
            return AgentResponse.Error.of(e.getMessage());
        }
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public AgentConfig getConfig() {
        return config;
    }

    /// Builds the ordered message list: system prompt, history, then user prompt.
    private List<ChatMessage> buildMessages(String prompt, Map<String, Object> context) {
        List<ChatMessage> messages = new ArrayList<>();

        if (!config.getRole().isEmpty()) {
            messages.add(
                    SystemMessage.from(
                            buildSystemPrompt(
                                    config.getRole(), config.getInstructions(), context)));
        }

        if (config.isMaintainContext() && !conversationHistory.isEmpty()) {
            messages.addAll(conversationHistory);
        }

        messages.add(UserMessage.from(prompt));
        return messages;
    }

    /// Composes a system prompt from role, instructions, and filtered context.
    ///
    /// Internal engine keys (`_`-prefixed, `retry_attempt`, `backtrack_reason`,
    /// `loop_iteration`) are excluded from the context section.
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

    /// Extracts response metadata including token usage and finish reason.
    private Map<String, Object> buildMetadata(ChatResponse response, Instant startTime) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("agent_id", id);
        metadata.put("model", config.getModel());
        metadata.put("timestamp", startTime.toString());
        metadata.put("duration_ms", Duration.between(startTime, Instant.now()).toMillis());

        var tokenUsage = response.metadata().tokenUsage();
        if (tokenUsage != null) {
            metadata.put("input_tokens", tokenUsage.inputTokenCount());
            metadata.put("output_tokens", tokenUsage.outputTokenCount());
            metadata.put("total_tokens", tokenUsage.totalTokenCount());
        }

        var finishReason = response.metadata().finishReason();
        if (finishReason != null) {
            metadata.put("finish_reason", finishReason.toString());
        }

        return metadata;
    }
}
