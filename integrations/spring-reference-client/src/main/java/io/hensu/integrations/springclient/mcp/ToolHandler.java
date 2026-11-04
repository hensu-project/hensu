package io.hensu.integrations.springclient.mcp;

import java.util.List;
import java.util.Map;

/// Contract for a locally-executed MCP tool.
///
/// Implementations are discovered by {@link ToolDispatcher} via Spring's
/// `List<ToolHandler>` injection. Register a new tool by creating a
/// `@Component` that implements this interface.
///
/// ### Relay context
/// The relay protocol needs two things from every tool:
///
/// 1. **Catalog** — the name, description and input schema, published to hensu-server
///    (`PUT /mcp/v1/catalog`) so an agent that declares the tool is offered its schema.
///    A tool missing from the catalog is a tool the agent never sees.
///
/// 2. **Call** — the work itself, run once for every call to the tool that the relay
///    pulls from the server and acknowledges.
///
/// {@link HensuMcpTransport} serves both: it publishes the catalog built from every
/// handler, and runs a handler for each tool call it pulls from the server. This
/// interface provides the data for both.
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
    /// Published in the relay's catalog. The agent uses this schema to decide
    /// what arguments to pass when it calls the tool.
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
    /// The returned map is posted back as the `result` of the call's
    /// `POST /mcp/v1/tasks/{token}/complete`.
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
