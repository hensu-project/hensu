package io.hensu.core.agent;

import io.hensu.core.plan.PlannedStep;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Sealed hierarchy for agent execution responses.
///
/// Represents the different types of responses an agent can produce:
/// - {@link TextResponse}: Standard text output (most common)
/// - {@link ToolRequest}: Agent requests to call a tool
/// - {@link PlanProposal}: Agent proposes a multi-step plan
/// - {@link Error}: Execution failed with an error
///
/// ### Pattern Matching Usage
/// {@snippet :
/// AgentResponse response = agent.execute(prompt, context);
/// String result = switch (response) {
///     case TextResponse t -> t.content();
///     case ToolRequest r -> "Tool call: " + r.toolName();
///     case PlanProposal p -> "Plan with " + p.steps().size() + " steps";
///     case Error e -> "Error: " + e.message();
/// };
/// }
///
/// @see Agent#execute for the execution entry point
/// @see io.hensu.core.plan.Planner for plan generation
public sealed interface AgentResponse
        permits AgentResponse.TextResponse,
                AgentResponse.ToolRequest,
                AgentResponse.PlanProposal,
                AgentResponse.Error {

    /// Returns when this response was created.
    Instant timestamp();

    /// Standard text output from agent execution.
    ///
    /// @param content the agent's text output, not null
    /// @param metadata additional execution metadata (tokens, latency), not null
    /// @param timestamp when the response was created, not null
    record TextResponse(String content, Map<String, Object> metadata, Instant timestamp)
            implements AgentResponse {

        public TextResponse {
            Objects.requireNonNull(content, "content must not be null");
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
            timestamp = timestamp != null ? timestamp : Instant.now();
        }

        public static TextResponse of(String content) {
            return new TextResponse(content, Map.of(), Instant.now());
        }

        public static TextResponse of(String content, Map<String, Object> metadata) {
            return new TextResponse(content, metadata, Instant.now());
        }
    }

    /// Agent requests to invoke a tool.
    ///
    /// Emitted when an agent determines it needs to call an external tool
    /// to complete its task. The executor should invoke the tool and
    /// continue the conversation with the result.
    ///
    /// @param toolName the tool to invoke, not null
    /// @param arguments tool parameters, not null
    /// @param reasoning agent's explanation for why this tool is needed
    /// @param timestamp when the response was created, not null
    record ToolRequest(
            String toolName, Map<String, Object> arguments, String reasoning, Instant timestamp)
            implements AgentResponse {

        public ToolRequest {
            Objects.requireNonNull(toolName, "toolName must not be null");
            arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
            reasoning = reasoning != null ? reasoning : "";
            timestamp = timestamp != null ? timestamp : Instant.now();
        }

        public static ToolRequest of(String toolName, Map<String, Object> arguments) {
            return new ToolRequest(toolName, arguments, "", Instant.now());
        }

        public static ToolRequest of(
                String toolName, Map<String, Object> arguments, String reasoning) {
            return new ToolRequest(toolName, arguments, reasoning, Instant.now());
        }
    }

    /// Agent proposes a multi-step execution plan.
    ///
    /// Emitted when an agent generates a plan for achieving a goal.
    /// The executor can review, modify, or execute the plan.
    ///
    /// @param steps the proposed execution steps, not null
    /// @param reasoning agent's explanation of the plan strategy
    /// @param timestamp when the response was created, not null
    record PlanProposal(List<PlannedStep> steps, String reasoning, Instant timestamp)
            implements AgentResponse {

        public PlanProposal {
            steps = steps != null ? List.copyOf(steps) : List.of();
            reasoning = reasoning != null ? reasoning : "";
            timestamp = timestamp != null ? timestamp : Instant.now();
        }

        public static PlanProposal of(List<PlannedStep> steps, String reasoning) {
            return new PlanProposal(steps, reasoning, Instant.now());
        }
    }

    /// Agent execution failed with an error.
    ///
    /// @param message error description, not null
    /// @param errorType classification of the error, not null
    /// @param cause the underlying exception, may be null
    /// @param timestamp when the error occurred, not null
    record Error(String message, ErrorType errorType, Throwable cause, Instant timestamp)
            implements AgentResponse {

        public enum ErrorType {
            TOOL_NOT_FOUND,
            INVALID_ARGUMENTS,
            TIMEOUT,
            RATE_LIMITED,
            UNKNOWN
        }

        public Error {
            Objects.requireNonNull(message, "message must not be null");
            errorType = errorType != null ? errorType : ErrorType.UNKNOWN;
            timestamp = timestamp != null ? timestamp : Instant.now();
        }

        public static Error from(Throwable cause) {
            return new Error(
                    cause.getMessage() != null
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName(),
                    ErrorType.UNKNOWN,
                    cause,
                    Instant.now());
        }

        public static Error of(String message) {
            return new Error(message, ErrorType.UNKNOWN, null, Instant.now());
        }

        public static Error of(String message, ErrorType type) {
            return new Error(message, type, null, Instant.now());
        }
    }
}
