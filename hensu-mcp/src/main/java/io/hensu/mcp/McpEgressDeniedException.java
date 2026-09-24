package io.hensu.mcp;

import java.io.Serial;

/// Thrown when a remote MCP server tried to send a request somewhere it was not
/// declared to reach.
///
/// Two bounds produce this, and the tighter one is the one that matters most.
///
/// The set of hosts named across the deployment's MCP declarations is the
/// allowlist for a run – the remote analogue of "the catalog is the allowlist".
/// Inside it, each connection is bound more tightly still: a redirect is
/// followed only when it stays on the very endpoint that connection dials,
/// scheme, host and port. Every request carries that server's bearer token and
/// declared headers, so a redirect to another declared server – or to another
/// port on the same host – would hand one server's credential to a second one
/// the operator never authorised to hold it.
///
/// This is a distinct type because the outcome is distinct: a refused egress is
/// a policy decision, not a transport fault, and the provider maps it onto
/// `ToolCallStatus.DENIED` rather than `FAILURE`. {@link McpConnection#callTool}
/// hands back a wire map rather than a typed result, so the type of the
/// exception is the only channel that distinction has.
///
/// @see StreamableHttpMcpConnection for where the allowlist is enforced
public class McpEgressDeniedException extends McpException {

    @Serial private static final long serialVersionUID = 6154493871286093771L;

    /// Creates an exception for a refused destination.
    ///
    /// @param message the reason, naming the destination and the allowlist, not null
    public McpEgressDeniedException(String message) {
        super(message);
    }
}
