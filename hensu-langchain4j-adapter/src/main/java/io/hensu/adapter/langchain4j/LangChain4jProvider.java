package io.hensu.adapter.langchain4j;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.spi.AgentProvider;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Logger;

/// LangChain4j implementation of AgentProvider. Auto-discovered via ServiceLoader.
public class LangChain4jProvider implements AgentProvider {

    private static final Logger logger = Logger.getLogger(LangChain4jProvider.class.getName());

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final double DEFAULT_TEMPERATURE = 0.7;

    @Override
    public String getName() {
        return "langchain4j";
    }

    @Override
    public boolean supportsModel(String modelName) {
        if (modelName == null) {
            return false;
        }

        // Support Claude models
        if (modelName.startsWith("claude")) {
            return true;
        }

        // Support OpenAI models
        if (modelName.startsWith("gpt") || modelName.startsWith("o1")) {
            return true;
        }

        // Support Google Gemini models
        if (modelName.startsWith("gemini") || modelName.startsWith("gemma")) {
            return true;
        }

        // Support DeepSeek models
        return modelName.startsWith("deepseek");
    }

    @Override
    public Agent createAgent(String agentId, AgentConfig config, Map<String, String> credentials) {
        logger.info("Creating LangChain4j agent: " + agentId + " with model: " + config.getModel());

        ChatLanguageModel model = createModel(config, credentials);
        return new LangChain4jAgent(agentId, config, model);
    }

    @Override
    public int getPriority() {
        return 100; // High priority - prefer LangChain4j when available
    }

    /// Create appropriate ChatLanguageModel based on model name.
    private ChatLanguageModel createModel(AgentConfig config, Map<String, String> credentials) {
        String modelName = config.getModel();

        if (modelName.startsWith("claude")) {
            return createAnthropicModel(config, credentials);
        } else if (modelName.startsWith("gpt") || modelName.startsWith("o1")) {
            return createOpenAiModel(config, credentials);
        } else if (modelName.startsWith("gemini") || modelName.startsWith("gemma")) {
            return createGoogleAiModel(config, credentials);
        } else if (modelName.startsWith("deepseek")) {
            return createDeepSeekModel(config, credentials);
        }

        throw new IllegalArgumentException("Unsupported model: " + modelName);
    }

    private ChatLanguageModel createAnthropicModel(
            AgentConfig config, Map<String, String> credentials) {
        String apiKey = credentials.get("anthropic_api_key");
        if (apiKey == null) {
            apiKey = credentials.get("ANTHROPIC_API_KEY");
        }
        if (apiKey == null) {
            throw new IllegalStateException(
                    "Anthropic API key not found. Provide 'anthropic_api_key' or 'ANTHROPIC_API_KEY' in credentials.");
        }

        AnthropicChatModel.AnthropicChatModelBuilder builder =
                AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(config.getModel())
                        .temperature(getTemperature(config))
                        .maxTokens(getMaxTokens(config))
                        .timeout(Duration.ofSeconds(getTimeout(config)));

        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }

        return builder.build();
    }

    private ChatLanguageModel createOpenAiModel(
            AgentConfig config, Map<String, String> credentials) {
        String apiKey = credentials.get("openai_api_key");
        if (apiKey == null) {
            apiKey = credentials.get("OPENAI_API_KEY");
        }
        if (apiKey == null) {
            throw new IllegalStateException(
                    "OpenAI API key not found. Provide 'openai_api_key' or 'OPENAI_API_KEY' in credentials.");
        }

        OpenAiChatModel.OpenAiChatModelBuilder builder =
                OpenAiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(config.getModel())
                        .temperature(getTemperature(config))
                        .maxTokens(getMaxTokens(config))
                        .timeout(Duration.ofSeconds(getTimeout(config)));

        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }

        if (config.getFrequencyPenalty() != null) {
            builder.frequencyPenalty(config.getFrequencyPenalty());
        }

        if (config.getPresencePenalty() != null) {
            builder.presencePenalty(config.getPresencePenalty());
        }

        return builder.build();
    }

    private ChatLanguageModel createGoogleAiModel(
            AgentConfig config, Map<String, String> credentials) {
        String apiKey = credentials.get("google_api_key");
        if (apiKey == null) {
            apiKey = credentials.get("GOOGLE_API_KEY");
        }
        if (apiKey == null) {
            throw new IllegalStateException(
                    "Google API key not found. Provide 'google_api_key' or 'GOOGLE_API_KEY' in credentials.");
        }

        GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder builder =
                GoogleAiGeminiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(config.getModel())
                        .temperature(getTemperature(config))
                        .maxOutputTokens(getMaxTokens(config))
                        .timeout(Duration.ofSeconds(getTimeout(config)));

        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }

        return builder.build();
    }

    /// Create DeepSeek model using OpenAI-compatible API. DeepSeek provides an OpenAI-compatible
    /// endpoint at <a href="https://api.deepseek.com">...</a>
    private ChatLanguageModel createDeepSeekModel(
            AgentConfig config, Map<String, String> credentials) {
        String apiKey = credentials.get("deepseek_api_key");
        if (apiKey == null) {
            apiKey = credentials.get("DEEPSEEK_API_KEY");
        }
        if (apiKey == null) {
            throw new IllegalStateException(
                    "DeepSeek API key not found. Provide 'deepseek_api_key' or 'DEEPSEEK_API_KEY' in credentials.");
        }

        OpenAiChatModel.OpenAiChatModelBuilder builder =
                OpenAiChatModel.builder()
                        .baseUrl("https://api.deepseek.com")
                        .apiKey(apiKey)
                        .modelName(config.getModel())
                        .temperature(getTemperature(config))
                        .maxTokens(getMaxTokens(config))
                        .timeout(Duration.ofSeconds(getTimeout(config)));

        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }

        if (config.getFrequencyPenalty() != null) {
            builder.frequencyPenalty(config.getFrequencyPenalty());
        }

        if (config.getPresencePenalty() != null) {
            builder.presencePenalty(config.getPresencePenalty());
        }

        return builder.build();
    }

    private double getTemperature(AgentConfig config) {
        return config.getTemperature() != null ? config.getTemperature() : DEFAULT_TEMPERATURE;
    }

    private int getMaxTokens(AgentConfig config) {
        return config.getMaxTokens() != null ? config.getMaxTokens() : DEFAULT_MAX_TOKENS;
    }

    private long getTimeout(AgentConfig config) {
        return config.getTimeout() != null ? config.getTimeout() : DEFAULT_TIMEOUT_SECONDS;
    }
}
