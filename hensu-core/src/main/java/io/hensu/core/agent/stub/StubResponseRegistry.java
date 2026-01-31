package io.hensu.core.agent.stub;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// Singleton registry for configurable stub responses.
///
/// Provides a flexible system for configuring mock responses used by {@link StubAgent}.
/// Supports both resource-based and programmatic response registration with
/// scenario-based organization.
///
/// ### Response Resolution Order
/// 1. Programmatically registered responses (highest priority)
/// 2. Node-specific resources: `/stubs/{scenario}/{nodeId}.txt`
/// 3. Agent-specific resources: `/stubs/{scenario}/{agentId}.txt`
/// 4. Default scenario resources: `/stubs/default/{nodeId|agentId}.txt`
/// 5. Returns null (triggers fallback generation in {@link StubAgent})
///
/// ### Scenario Selection
/// - Context variable: `context.put("stub_scenario", "low_score")`
/// - System property: `-Dhensu.stub.scenario=backtrack`
/// - Default: `"default"`
///
/// ### Template Variables
/// Response templates support `{{key}}` syntax for context variable substitution.
///
/// @implNote Thread-safe singleton. Uses {@link ConcurrentHashMap} for storage.
/// Resource loading is cached to avoid repeated file I/O.
///
/// @see StubAgent for response usage
/// @see StubAgentProvider for enabling stub mode
public class StubResponseRegistry {

    private static final Logger logger = Logger.getLogger(StubResponseRegistry.class.getName());
    private static final String STUB_RESOURCE_BASE = "/stubs/";
    private static final String DEFAULT_SCENARIO = "default";

    private static final StubResponseRegistry INSTANCE = new StubResponseRegistry();

    private final Map<String, Map<String, String>> registeredResponses = new ConcurrentHashMap<>();
    private final Map<String, String> resourceCache = new ConcurrentHashMap<>();

    private StubResponseRegistry() {
        // Private constructor for singleton
    }

    /// Returns the singleton registry instance.
    ///
    /// @return the shared registry instance, never null
    public static StubResponseRegistry getInstance() {
        return INSTANCE;
    }

    /// Registers a stub response programmatically for a specific scenario.
    ///
    /// Programmatic responses take the highest priority in resolution order.
    ///
    /// @apiNote **Side effects**:
    /// - Modifies internal response registry
    /// - Overwrites existing response if key already registered for scenario
    ///
    /// @param scenario the scenario name (e.g., "low_score", "backtrack"), not null
    /// @param key the node ID or agent ID to match, not null
    /// @param response the response content (may contain `{{key}}` placeholders), not null
    /// @throws NullPointerException if any parameter is null
    public void registerResponse(String scenario, String key, String response) {
        registeredResponses
                .computeIfAbsent(scenario, _ -> new ConcurrentHashMap<>())
                .put(key, response);
        logger.fine("Registered stub response for scenario=" + scenario + ", key=" + key);
    }

    /// Registers a stub response for the default scenario.
    ///
    /// Convenience method equivalent to `registerResponse("default", key, response)`.
    ///
    /// @param key the node ID or agent ID to match, not null
    /// @param response the response content, not null
    /// @throws NullPointerException if key or response is null
    public void registerResponse(String key, String response) {
        registerResponse(DEFAULT_SCENARIO, key, response);
    }

    /// Clears all registered responses and cached resources.
    ///
    /// @apiNote **Side effects**:
    /// - Clears all programmatically registered responses
    /// - Clears resource cache (resources will be reloaded on next access)
    public void clearResponses() {
        registeredResponses.clear();
        resourceCache.clear();
    }

    /// Clears responses for a specific scenario.
    ///
    /// @apiNote **Side effects**:
    /// - Removes programmatic responses for the scenario
    /// - Clears cached resources for the scenario
    ///
    /// @param scenario the scenario to clear, not null
    /// @throws NullPointerException if scenario is null
    public void clearScenario(String scenario) {
        registeredResponses.remove(scenario);
        resourceCache.keySet().removeIf(key -> key.startsWith(scenario + "/"));
    }

    /// Resolves a stub response for the given execution context.
    ///
    /// Searches through programmatic registrations and resource files in priority
    /// order until a response is found. Template variables (`{{key}}`) in the
    /// response are substituted with context values.
    ///
    /// @param nodeId the current node ID (for node-specific responses), may be null
    /// @param agentId the agent ID, not null
    /// @param context execution context for scenario detection and variable substitution, may be
    /// null
    /// @param prompt the original prompt (unused, reserved for future use), may be null
    /// @return resolved response with variables substituted, or null if no response configured
    public String getResponse(
            String nodeId, String agentId, Map<String, Object> context, String prompt) {
        String scenario = getScenario(context);
        String response;

        if (nodeId != null) {
            response = getRegisteredResponse(scenario, nodeId);
            if (response != null) {
                logger.info(
                        "[STUB] Using registered response for node: "
                                + nodeId
                                + " (scenario: "
                                + scenario
                                + ")");
                return processResponse(response, context);
            }
        }

        if (agentId != null) {
            response = getRegisteredResponse(scenario, agentId);
            if (response != null) {
                logger.info(
                        "[STUB] Using registered response for agent: "
                                + agentId
                                + " (scenario: "
                                + scenario
                                + ")");
                return processResponse(response, context);
            }
        }

        if (nodeId != null) {
            response = loadResourceResponse(scenario, nodeId);
            if (response != null) {
                logger.info(
                        "[STUB] Loaded resource response for node: "
                                + nodeId
                                + " (scenario: "
                                + scenario
                                + ")");
                return processResponse(response, context);
            }
        }

        if (agentId != null) {
            response = loadResourceResponse(scenario, agentId);
            if (response != null) {
                logger.info(
                        "[STUB] Loaded resource response for agent: "
                                + agentId
                                + " (scenario: "
                                + scenario
                                + ")");
                return processResponse(response, context);
            }
        }

        if (!DEFAULT_SCENARIO.equals(scenario)) {
            if (nodeId != null) {
                response = loadResourceResponse(DEFAULT_SCENARIO, nodeId);
                if (response != null) {
                    logger.info("[STUB] Loaded default resource for node: " + nodeId);
                    return processResponse(response, context);
                }
            }

            if (agentId != null) {
                response = loadResourceResponse(DEFAULT_SCENARIO, agentId);
                if (response != null) {
                    logger.info("[STUB] Loaded default resource for agent: " + agentId);
                    return processResponse(response, context);
                }
            }
        }

        logger.info(
                "[STUB] Generating fallback response for: " + (nodeId != null ? nodeId : agentId));
        return null;
    }

    /// Determines the current scenario from context or system property.
    ///
    /// @param context execution context to check for `stub_scenario`, may be null
    /// @return scenario name, defaults to "default"
    private String getScenario(Map<String, Object> context) {
        if (context != null) {
            Object scenario = context.get("stub_scenario");
            if (scenario != null) {
                return scenario.toString();
            }
        }

        String sysProp = System.getProperty("hensu.stub.scenario");
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp;
        }

        return DEFAULT_SCENARIO;
    }

    /// Retrieves a programmatically registered response.
    ///
    /// @param scenario the scenario to search, not null
    /// @param key the node or agent ID, not null
    /// @return registered response or null if not found
    private String getRegisteredResponse(String scenario, String key) {
        Map<String, String> scenarioResponses = registeredResponses.get(scenario);
        if (scenarioResponses != null) {
            return scenarioResponses.get(key);
        }
        return null;
    }

    /// Loads a response from resource files with caching.
    ///
    /// @param scenario the scenario subdirectory, not null
    /// @param key the file name (without .txt extension), not null
    /// @return file contents or null if not found
    private String loadResourceResponse(String scenario, String key) {
        String cacheKey = scenario + "/" + key;

        if (resourceCache.containsKey(cacheKey)) {
            String cached = resourceCache.get(cacheKey);
            return cached.isEmpty() ? null : cached;
        }

        String resourcePath = STUB_RESOURCE_BASE + scenario + "/" + key + ".txt";
        String content = loadResource(resourcePath);

        resourceCache.put(cacheKey, content != null ? content : "");

        return content;
    }

    /// Loads raw content from a classpath resource.
    ///
    /// @param path absolute resource path, not null
    /// @return file contents or null if resource not found
    private String loadResource(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                return null;
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            logger.warning("Failed to load stub resource: " + path + " - " + e.getMessage());
            return null;
        }
    }

    /// Processes response template by substituting `{{key}}` placeholders.
    ///
    /// @param response template string with placeholders, not null
    /// @param context variable values for substitution, may be null
    /// @return processed response with variables replaced
    private String processResponse(String response, Map<String, Object> context) {
        if (context == null || response == null) {
            return response;
        }

        Pattern pattern = Pattern.compile("\\{\\{(\\w+)}}");
        Matcher matcher = pattern.matcher(response);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = context.get(key);
            String replacement = value != null ? Matcher.quoteReplacement(value.toString()) : "";
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /// Returns the set of available scenarios.
    ///
    /// Includes the default scenario and all programmatically registered scenarios.
    /// Does not scan resource directories.
    ///
    /// @return set of scenario names, never null, always contains "default"
    public Set<String> getAvailableScenarios() {
        Set<String> scenarios = new HashSet<>();
        scenarios.add(DEFAULT_SCENARIO);
        scenarios.addAll(registeredResponses.keySet());
        return scenarios;
    }
}
