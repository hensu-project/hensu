package io.hensu.integrations.springclient.mcp;

import java.util.List;
import java.util.Map;

/// Contract for a locally-executed MCP tool.
///
/// Implementations are discovered by {@link ToolDispatcher} via Spring's
/// `List<ToolHandler>` injection. Register a new tool by creating a
/// `@Component` that implements this interface.
///
/// ### JSON-RPC context
/// The MCP protocol requires two interactions before any tool can be called:
///
/// 1. **`tools/list`** — hensu-server discovers available tools at execution start
///    so `LlmPlanner` can inject their names, descriptions, and parameter schemas
///    into the planning prompt. Without this, the LLM sees zero tools and will
///    never plan a tool call.
///
/// 2. **`tools/call`** — hensu-server dispatches execution after the LLM plan
///    decides to invoke a specific tool.
///
/// Both requests arrive via the SSE split-pipe and are handled by
/// {@link HensuMcpTransport}. This interface provides the data for both.
///
/// @see io.hensu.integrations.springclient.tools.FetchCustomerDataTool
/// @see io.hensu.integrations.springclient.tools.CalculateRiskScoreTool
public interface ToolHandler {

    /// Returns the tool name as referenced in the workflow and planning prompt.
    ///
    /// @return tool name, not null, not blank
    String name();

    /// Returns a human-readable description injected into the LLM planning prompt.
    ///
    /// The planner formats this as:
    /// `- **tool_name**: description\n  Parameters: ...`
    ///
    /// Write it from the LLM's perspective: what does calling this tool achieve?
    ///
    /// @return description, not null, not blank
    String description();

    /// Returns the JSON Schema for this tool's input parameters.
    ///
    /// Used in the `tools/list` MCP response. The LLM planner uses this schema
    /// to understand what arguments to pass when it decides to call the tool.
    ///
    /// ### Minimum structure
    /// ```json
    /// {
    ///   "type": "object",
    ///   "properties": { "paramName": { "type": "string", "description": "..." } },
    ///   "required": ["paramName"]
    /// }
    /// ```
    ///
    /// @return JSON Schema as a nested map, never null
    Map<String, Object> inputSchema();

    /// Executes the tool with the provided arguments.
    ///
    /// The returned map is serialized as the `result` field in the JSON-RPC
    /// response posted back to `/mcp/message`.
    ///
    /// @param arguments tool arguments from `params.arguments`, never null
    /// @return tool execution result, never null
    Map<String, Object> execute(Map<String, Object> arguments);

    // Convenience factory for building inputSchema maps without external deps

    static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    static Map<String, Object> stringParam(String description) {
        return Map.of("type", "string", "description", description);
    }
}
