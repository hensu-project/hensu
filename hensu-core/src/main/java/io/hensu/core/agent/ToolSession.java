package io.hensu.core.agent;

import io.hensu.core.tool.ToolCallResult;

/// Call-scoped session for multi-turn tool interactions with an agent.
///
/// Each session encapsulates the message state for one tool loop execution.
/// Sessions are call-scoped (not stateful on the agent) so that
/// {@code ParallelNodeExecutor} fan-out can share one agent instance
/// across branches without cross-branch bleed.
///
/// ### Termination Semantics
/// The sealed {@link AgentResponse} hierarchy IS the signal:
/// - {@link AgentResponse.TextResponse} — loop done, agent produced final answer
/// - {@link AgentResponse.Error} — loop done, agent errored
/// - {@link AgentResponse.ToolRequest} — loop continues (another tool call round)
///
/// @see ToolCapable#openToolSession for session creation
public interface ToolSession {

    /// Sends the initial prompt with tool definitions to the agent.
    ///
    /// @return first agent response (tool request or final answer), never null
    AgentResponse start();

    /// Feeds a tool execution result back to the agent.
    ///
    /// @param result the tool call outcome, not null
    /// @return next agent response, never null
    AgentResponse submit(ToolCallResult result);

    /// Discards intermediate tool-call/result messages to free context window.
    ///
    /// Retains system message, original user prompt, and last assistant message.
    /// Implementations may no-op if the session is already compact.
    void compact();

    /// Releases session resources and flushes the final prompt/answer pair
    /// to the agent's shared conversation history (if applicable).
    ///
    /// Called in a finally block by the loop driver. Implementations that
    /// hold no resources may no-op.
    void close();
}
