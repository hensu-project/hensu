package io.hensu.mcp;

import java.io.Serial;

/// Thrown when an agent's argument cannot be sent the way the server's own
/// schema says it must be.
///
/// Revision 2026-07-28 lets a server mark a tool parameter with `x-mcp-header`,
/// and a client then has to repeat that argument's value in an
/// `Mcp-Param-{Name}` request header. Only a string, a boolean or an integer
/// within the IEEE 754 safe range has a header form. An argument of any other
/// shape – an object, a list, `3.5`, `2^60` – has none, and sending the request
/// with the header left out would draw a `-32020` (`HeaderMismatch`) that tells
/// the agent nothing about what to change.
///
/// This is a distinct type for the same reason {@link McpEgressDeniedException}
/// is: the outcome is distinct. Nothing left the machine, the connection is
/// healthy, and the agent can correct the argument and call again, so the
/// provider maps this onto `ToolCallStatus.VALIDATION_FAILED` rather than
/// `FAILURE`.
///
/// @see McpParameterHeaders for the conversion rules
public class McpInvalidArgumentException extends McpException {

    @Serial private static final long serialVersionUID = -3928840617349012275L;

    /// Creates an exception for an argument with no header form.
    ///
    /// @param message the reason, naming the argument and its expected type, not null
    public McpInvalidArgumentException(String message) {
        super(message);
    }
}
