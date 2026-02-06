package io.hensu.adapter.langchain4j;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.spi.AgentProvider;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Logger;

/// LangChain4j implementation of {@link AgentProvider}.
///
/// Creates {@link ChatModel} instances for supported AI providers and wraps them
/// in {@link LangChain4jAgent}. Supports Anthropic (Claude), OpenAI (GPT/o1),
/// Google (Gemini/Gemma), and DeepSeek models.
///
/// DeepSeek uses the OpenAI-compatible API with a custom base URL.
///
/// @implNote Stateless and thread-safe. Each call to {@link #createAgent} creates
/// a new model instance — no shared mutable state.
///
/// @see LangChain4jAgent for the agent implementation
/// @see AgentProvider for the provider contract
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
        if (modelName == null) return false;
        return modelName.startsWith("claude")
                || modelName.startsWith("gpt")
                || modelName.startsWith("o1")
                || modelName.startsWith("gemini")
                || modelName.startsWith("gemma")
                || modelName.startsWith("deepseek");
    }

    @Override
    public Agent createAgent(String agentId, AgentConfig config, Map<String, String> credentials) {
        logger.info("Creating LangChain4j agent: " + agentId + " with model: " + config.getModel());
        ChatModel model = createModel(config, credentials);
        return new LangChain4jAgent(agentId, config, model);
    }

    @Override
    public int getPriority() {
        return 100;
    }

    /// Creates the appropriate {@link ChatModel} based on model name prefix.
    ///
    /// @param config agent configuration containing the model name, not null
    /// @param credentials API keys keyed by provider name, not null
    /// @return configured chat model, never null
    /// @throws IllegalArgumentException if model name is not supported
    /// @throws IllegalStateException if required API key is missing
    private ChatModel createModel(AgentConfig config, Map<String, String> credentials) {
        String modelName = config.getModel();

        if (modelName.startsWith("claude")) {
            return createAnthropicModel(config, credentials);
        } else if (modelName.startsWith("gpt") || modelName.startsWith("o1")) {
            return createOpenAiModel(config, credentials, null);
        } else if (modelName.startsWith("gemini") || modelName.startsWith("gemma")) {
            return createGoogleAiModel(config, credentials);
        } else if (modelName.startsWith("deepseek")) {
            return createOpenAiModel(config, credentials, "https://api.deepseek.com");
        }

        throw new IllegalArgumentException("Unsupported model: " + modelName);
    }

    private ChatModel createAnthropicModel(AgentConfig config, Map<String, String> credentials) {
        String apiKey = requireApiKey(credentials, "anthropic_api_key", "ANTHROPIC_API_KEY");

        var builder =
                AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(config.getModel())
                        .temperature(getTemperature(config))
                        .maxTokens(getMaxTokens(config))
                        .timeout(Duration.ofSeconds(getTimeout(config)));

        if (config.getTopP() != null) builder.topP(config.getTopP());

        return builder.build();
    }

    /// Creates an OpenAI-compatible model, used for both OpenAI and DeepSeek
    /// (via base URL override).
    ///
    /// @param config agent configuration, not null
    /// @param credentials API keys, not null
    /// @param baseUrl custom API endpoint, may be null (uses OpenAI default)
    /// @return configured model, never null
    private ChatModel createOpenAiModel(
            AgentConfig config, Map<String, String> credentials, String baseUrl) {
        String apiKey;
        if (baseUrl != null && baseUrl.contains("deepseek")) {
            apiKey = requireApiKey(credentials, "deepseek_api_key", "DEEPSEEK_API_KEY");
        } else {
            apiKey = requireApiKey(credentials, "openai_api_key", "OPENAI_API_KEY");
        }

        var builder =
                OpenAiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(config.getModel())
                        .temperature(getTemperature(config))
                        .maxTokens(getMaxTokens(config))
                        .timeout(Duration.ofSeconds(getTimeout(config)));

        if (baseUrl != null) builder.baseUrl(baseUrl);
        if (config.getTopP() != null) builder.topP(config.getTopP());
        if (config.getFrequencyPenalty() != null)
            builder.frequencyPenalty(config.getFrequencyPenalty());
        if (config.getPresencePenalty() != null)
            builder.presencePenalty(config.getPresencePenalty());

        return builder.build();
    }

    private ChatModel createGoogleAiModel(AgentConfig config, Map<String, String> credentials) {
        String apiKey = requireApiKey(credentials, "google_api_key", "GOOGLE_API_KEY");

        var builder =
                GoogleAiGeminiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(config.getModel())
                        .temperature(getTemperature(config))
                        .maxOutputTokens(getMaxTokens(config))
                        .timeout(Duration.ofSeconds(getTimeout(config)));

        if (config.getTopP() != null) builder.topP(config.getTopP());

        return builder.build();
    }

    /// Looks up an API key from credentials, trying each key name in order.
    ///
    /// @param credentials credential map to search, not null
    /// @param keyNames candidate key names in priority order
    /// @return the first non-null value found, never null
    /// @throws IllegalStateException if no key name resolves to a value
    private String requireApiKey(Map<String, String> credentials, String... keyNames) {
        for (String keyName : keyNames) {
            String value = credentials.get(keyName);
            if (value != null) return value;
        }
        throw new IllegalStateException(
                "API key not found. Provide one of: " + String.join(", ", keyNames));
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
