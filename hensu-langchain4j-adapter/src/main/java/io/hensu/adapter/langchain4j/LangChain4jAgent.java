package io.hensu.adapter.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// LangChain4j implementation of Agent.
public class LangChain4jAgent implements Agent {

    private static final Logger logger = Logger.getLogger(LangChain4jAgent.class.getName());

    private final String id;
    private final AgentConfig config;
    private final ChatLanguageModel model;
    private final List<ChatMessage> conversationHistory;

    public LangChain4jAgent(String id, AgentConfig config, ChatLanguageModel model) {
        this.id = id;
        this.config = config;
        this.model = model;
        this.conversationHistory = new ArrayList<>();
    }

    @Override
    public AgentResponse execute(String prompt, Map<String, Object> context) {
        Instant startTime = Instant.now();

        try {
            logger.fine("Agent '" + id + "' executing with role: " + config.getRole());

            // Resolve template variables in prompt
            String resolvedPrompt = resolveTemplate(prompt, context);

            // Build messages
            List<ChatMessage> messages = buildMessages(resolvedPrompt, context);

            // Execute chat
            Response<AiMessage> response = model.generate(messages);

            if (response == null) {
                return createErrorResponse("No response from model", startTime);
            }

            AiMessage aiMessage = response.content();
            String output = aiMessage.text();

            // Store in conversation history if needed
            if (config.isMaintainContext()) {
                conversationHistory.add(UserMessage.from(resolvedPrompt));
                conversationHistory.add(aiMessage);
            }

            // Build metadata
            Map<String, Object> metadata = buildMetadata(response, startTime);

            logger.fine("Agent '" + id + "' completed successfully");

            return AgentResponse.builder().success(true).output(output).metadata(metadata).build();

        } catch (Exception e) {
            logger.severe("Agent '" + id + "' execution failed: " + e.getMessage());
            return createErrorResponse(e.getMessage(), startTime);
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

    private List<ChatMessage> buildMessages(String prompt, Map<String, Object> context) {
        List<ChatMessage> messages = new ArrayList<>();

        // Add system message with role
        if (!config.getRole().isEmpty()) {
            String systemPrompt =
                    buildSystemPrompt(config.getRole(), config.getInstructions(), context);
            messages.add(SystemMessage.from(systemPrompt));
        }

        // Add conversation history if maintaining context
        if (config.isMaintainContext() && !conversationHistory.isEmpty()) {
            messages.addAll(conversationHistory);
        }

        // Add current user prompt
        messages.add(UserMessage.from(prompt));

        return messages;
    }

    private String buildSystemPrompt(
            String role, String instructions, Map<String, Object> context) {
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("You are a ").append(role).append(".\n\n");

        if (instructions != null && !instructions.isEmpty()) {
            systemPrompt.append(instructions).append("\n\n");
        }

        // Add relevant context
        if (context != null && !context.isEmpty()) {
            systemPrompt.append("Context information:\n");
            context.forEach(
                    (key, value) -> {
                        if (shouldIncludeInSystemPrompt(key)) {
                            systemPrompt
                                    .append("- ")
                                    .append(key)
                                    .append(": ")
                                    .append(value)
                                    .append("\n");
                        }
                    });
        }

        return systemPrompt.toString();
    }

    private boolean shouldIncludeInSystemPrompt(String key) {
        return !key.startsWith("_")
                && !key.equals("retry_attempt")
                && !key.equals("backtrack_reason")
                && !key.equals("loop_iteration");
    }

    private Map<String, Object> buildMetadata(Response<AiMessage> response, Instant startTime) {
        Map<String, Object> metadata = new HashMap<>();

        metadata.put("agent_id", id);
        metadata.put("model", config.getModel());
        metadata.put("timestamp", startTime.toString());
        metadata.put("duration_ms", Duration.between(startTime, Instant.now()).toMillis());

        if (response.tokenUsage() != null) {
            metadata.put("input_tokens", response.tokenUsage().inputTokenCount());
            metadata.put("output_tokens", response.tokenUsage().outputTokenCount());
            metadata.put("total_tokens", response.tokenUsage().totalTokenCount());
        }

        if (response.finishReason() != null) {
            metadata.put("finish_reason", response.finishReason().toString());
        }

        return metadata;
    }

    private AgentResponse createErrorResponse(String errorMessage, Instant startTime) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("agent_id", id);
        metadata.put("error", errorMessage);
        metadata.put("timestamp", startTime.toString());
        metadata.put("duration_ms", Duration.between(startTime, Instant.now()).toMillis());

        return AgentResponse.builder()
                .success(false)
                .output("Error: " + errorMessage)
                .metadata(metadata)
                .build();
    }

    private String resolveTemplate(String template, Map<String, Object> context) {
        String result = template;
        Pattern pattern = Pattern.compile("\\{([^}]+)}");
        Matcher matcher = pattern.matcher(template);

        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = context.get(key);
            String replacement = value != null ? value.toString() : "";
            result = result.replace(matcher.group(0), replacement);
        }

        return result;
    }

    public void clearHistory() {
        conversationHistory.clear();
    }

    public int getHistorySize() {
        return conversationHistory.size();
    }
}
