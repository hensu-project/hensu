package io.hensu.core.agent;

import io.hensu.core.tool.ToolDefinition;
import java.util.List;
import java.util.Map;

/// Capability interface for agents that support multi-turn tool execution.
///
/// Agents implement this alongside {@link Agent} to indicate they can
/// participate in the tool loop driven by the engine's {@code ToolLoopRunner}.
///
/// @see ToolSession for the call-scoped session contract
public interface ToolCapable {

    /// Opens a new call-scoped tool session.
    ///
    /// @param prompt the resolved prompt text, not null
    /// @param context execution context variables, not null
    /// @param tools filtered tool definitions available to this agent, not null
    /// @return a new session for driving the tool loop, never null
    ToolSession openToolSession(
            String prompt, Map<String, Object> context, List<ToolDefinition> tools);
}
