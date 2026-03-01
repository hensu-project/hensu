package io.hensu.integrations.springclient.mcp;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/// Routes `tools/call` JSON-RPC requests to the matching {@link ToolHandler}.
///
/// All `@Component` beans implementing {@link ToolHandler} are auto-discovered
/// via Spring's list injection and indexed by {@link ToolHandler#name()}.
///
/// If no handler is registered for a requested tool name, a structured error
/// result is returned instead of throwing — the error is propagated back to
/// hensu-server via the JSON-RPC response so the workflow can handle it.
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
    /// Called when hensu-server sends a `tools/list` JSON-RPC request at the
    /// start of a planning-mode execution. The returned list is sent back as the
    /// `result.tools` field — `LlmPlanner` injects each tool's name, description,
    /// and parameter schema into the planning prompt so the LLM knows what to call.
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
    /// @param toolName  the tool name from `params.name`
    /// @param arguments the tool arguments from `params.arguments`
    /// @return execution result, or an error map if the tool is not registered
    public Map<String, Object> dispatch(String toolName, Map<String, Object> arguments) {
        ToolHandler handler = handlers.get(toolName);

        if (handler == null) {
            LOG.warn("No handler registered for tool: {}", toolName);
            return Map.of(
                    "error", "Unknown tool: " + toolName,
                    "registeredTools", handlers.keySet().toString());
        }

        LOG.debug("Dispatching tool call: name={}, args={}", toolName, arguments);
        return handler.execute(arguments);
    }
}
