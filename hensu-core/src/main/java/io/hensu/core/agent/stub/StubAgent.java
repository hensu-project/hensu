package io.hensu.core.agent.stub;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.agent.AgentResponse.TextResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/// Testing agent that returns mock responses without calling external APIs.
///
/// Useful for testing workflow logic without consuming API tokens. Responses
/// can be configured via resource files or programmatically through
/// {@link StubResponseRegistry}.
///
/// ### Response Resolution Order
/// 1. Programmatically registered responses via {@link StubResponseRegistry}
/// 2. Resource files at `/stubs/{scenario}/{nodeId}.txt` or `/stubs/{scenario}/{agentId}.txt`
/// 3. Default scenario resources at `/stubs/default/{nodeId|agentId}.txt`
/// 4. Auto-generated response based on prompt analysis
///
/// ### Configuration
/// - Resource files: Create `/stubs/{scenario}/{agentId}.txt` in resources
/// - Programmatic: `StubResponseRegistry.getInstance().registerResponse(...)`
/// - Scenario selection: Set `stub_scenario` in execution context
///
/// @implNote Thread-safe. Uses singleton {@link StubResponseRegistry} for response lookup.
/// Multiple workflow threads can use the same stub agent concurrently.
///
/// @see StubAgentProvider for enabling stub mode
/// @see StubResponseRegistry for response configuration
public class StubAgent implements Agent {

    private static final Logger logger = Logger.getLogger(StubAgent.class.getName());

    private final String id;
    private final AgentConfig config;
    private final StubResponseRegistry responseRegistry;

    /// Creates a new stub agent with the given configuration.
    ///
    /// @param id unique agent identifier, not null
    /// @param config agent configuration, not null
    /// @throws NullPointerException if id or config is null
    public StubAgent(String id, AgentConfig config) {
        this.id = id;
        this.config = config;
        this.responseRegistry = StubResponseRegistry.getInstance();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public AgentConfig getConfig() {
        return config;
    }

    /// Executes the agent by returning a configured or generated stub response.
    ///
    /// Looks up the response using {@link StubResponseRegistry} based on the current
    /// node ID (from `current_node` context key) and agent ID. Falls back to
    /// auto-generated responses if no configured response is found.
    ///
    /// @param prompt the instruction/prompt for the agent, not null
    /// @param context execution context with variables, not null
    /// @return stub response with metadata indicating stub execution, never null
    @Override
    public AgentResponse execute(String prompt, Map<String, Object> context) {
        logger.info("[STUB] Agent '" + id + "' received prompt (" + prompt.length() + " chars)");

        String nodeId = context != null ? (String) context.get("current_node") : null;

        String response = responseRegistry.getResponse(nodeId, id, context, prompt);

        if (response == null) {
            response = generateMockResponse(prompt, context);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("stub", true);
        metadata.put("model", config.getModel());
        metadata.put("prompt_length", prompt.length());
        metadata.put(
                "scenario",
                context != null ? context.getOrDefault("stub_scenario", "default") : "default");

        return TextResponse.of(response, metadata);
    }

    /// Generates a mock response when no configured response is found.
    ///
    /// If the prompt requests JSON output (contains "json" or "output as"),
    /// attempts to generate valid JSON with placeholder values.
    ///
    /// @param prompt the original prompt, not null
    /// @param context execution context, may be null
    /// @return generated stub response, never null
    private String generateMockResponse(String prompt, Map<String, Object> context) {
        String lowerPrompt = prompt.toLowerCase();

        if (lowerPrompt.contains("json") || lowerPrompt.contains("output as")) {
            return generateJsonResponse(prompt);
        }

        return String.format(
                """
                        [STUB RESPONSE from %s]

                        This is a stub response for testing purposes.
                        Agent: %s
                        Model: %s
                        Role: %s

                        The actual prompt was:
                        %s""",
                id,
                id,
                config.getModel(),
                config.getRole(),
                prompt.length() > 500 ? prompt.substring(0, 500) + "..." : prompt);
    }

    /// Generates a JSON response by parsing expected keys from the prompt.
    ///
    /// Extracts field names from patterns like `"key":` in the prompt and
    /// generates placeholder values.
    ///
    /// @param prompt the prompt containing JSON structure hints, not null
    /// @return generated JSON string, never null
    private String generateJsonResponse(String prompt) {
        StringBuilder json = new StringBuilder("{\n");

        String[] lines = prompt.split("\n");
        boolean firstKey = true;

        for (String line : lines) {
            int colonIndex = line.indexOf("\":");
            if (colonIndex > 0) {
                int quoteStart = line.lastIndexOf("\"", colonIndex - 1);
                if (quoteStart >= 0) {
                    String key = line.substring(quoteStart + 1, colonIndex);
                    if (!key.isEmpty() && !key.contains(" ") && !key.contains("<")) {
                        if (!firstKey) {
                            json.append(",\n");
                        }
                        json.append("    \"")
                                .append(key)
                                .append("\": \"stub_value_for_")
                                .append(key)
                                .append("\"");
                        firstKey = false;
                    }
                }
            }
        }

        if (firstKey) {
            json.append("    \"result\": \"stub_response\",\n");
            json.append("    \"status\": \"success\",\n");
            json.append("    \"message\": \"This is a stub JSON response\"");
        }

        json.append("\n}");
        return json.toString();
    }
}
