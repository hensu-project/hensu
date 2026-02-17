package io.hensu.server.mcp;

import java.io.Serial;

/// Exception thrown when MCP operations fail.
///
/// Wraps errors from MCP protocol communication including:
/// - Connection failures
/// - Tool invocation errors
/// - Protocol errors
/// - Timeout errors
public class McpException extends RuntimeException {

    @Serial private static final long serialVersionUID = 5486229795475543465L;

    private final String toolName;
    private final String errorCode;

    /// Creates an MCP exception with a message.
    ///
    /// @param message the error message
    public McpException(String message) {
        super(message);
        this.toolName = null;
        this.errorCode = null;
    }

    /// Creates an MCP exception with a cause.
    ///
    /// @param message the error message
    /// @param cause the underlying cause
    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.toolName = null;
        this.errorCode = null;
    }

    /// Creates an MCP exception for a tool invocation failure.
    ///
    /// @param toolName the tool that failed
    /// @param message the error message
    /// @param errorCode optional MCP error code
    public McpException(String toolName, String message, String errorCode) {
        super(String.format("Tool '%s' failed: %s", toolName, message));
        this.toolName = toolName;
        this.errorCode = errorCode;
    }

    /// Returns the name of the tool that failed.
    ///
    /// @return tool name, or null if not tool-specific
    public String getToolName() {
        return toolName;
    }

    /// Returns the MCP error code.
    ///
    /// @return error code, or null if not available
    public String getErrorCode() {
        return errorCode;
    }

    /// Creates an exception for a connection failure.
    ///
    /// @param endpoint the MCP endpoint
    /// @param cause the underlying cause
    /// @return new exception
    public static McpException connectionFailed(String endpoint, Throwable cause) {
        return new McpException("Failed to connect to MCP server at " + endpoint, cause);
    }

    /// Creates an exception for a timeout.
    ///
    /// @param toolName the tool that timed out
    /// @param timeoutMs the timeout in milliseconds
    /// @return new exception
    public static McpException timeout(String toolName, long timeoutMs) {
        return new McpException(
                toolName, "Operation timed out after " + timeoutMs + "ms", "TIMEOUT");
    }
}
