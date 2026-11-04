package io.hensu.integrations.springclient.mcp;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/// Routes the tool calls the relay pulls to the matching {@link ToolHandler}, and
/// describes every handler for the catalog the relay publishes.
///
/// All `@Component` beans implementing {@link ToolHandler} are auto-discovered
/// via Spring's list injection and indexed by {@link ToolHandler#name()}.
@Component
public class ToolDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(ToolDispatcher.class);

    private final Map<String, ToolHandler> handlers;

    public ToolDispatcher(List<ToolHandler> handlers) {
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(ToolHandler::name, Function.identity()));
        LOG.info("Registered MCP tools: {}", this.handlers.keySet());
    }

    /// Returns MCP-formatted descriptors for all registered tools.
    ///
    /// Published as the relay's catalog: hensu-server offers each tool's name,
    /// description and parameter schema to the agents that declare it.
    ///
    /// @return list of MCP tool descriptor maps, never null
    public List<Map<String, Object>> listTools() {
        return handlers.values().stream()
                .map(h -> Map.<String, Object>of(
                        "name", h.name(),
                        "description", h.description(),
                        "inputSchema", h.inputSchema()))
                .toList();
    }

    /// Dispatches a tool call to the matching handler.
    ///
    /// @param toolName  the tool name of the pulled task
    /// @param arguments the tool arguments of the pulled task
    /// @return execution result, never null
    /// @throws IllegalArgumentException if no handler is registered for the tool – the
    ///     caller reports it as an error outcome, never as a result the agent could
    ///     mistake for success
    public Map<String, Object> dispatch(String toolName, Map<String, Object> arguments) {
        ToolHandler handler = handlers.get(toolName);

        if (handler == null) {
            LOG.warn("No handler registered for tool: {}", toolName);
            throw new IllegalArgumentException(
                    "Unknown tool: " + toolName + "; registered: " + handlers.keySet());
        }

        LOG.debug("Dispatching tool call: name={}, args={}", toolName, arguments);
        return handler.execute(arguments);
    }
}
