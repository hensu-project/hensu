package io.hensu.mcp;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/// Decides whether a redirect may be followed with one server's credential.
///
/// Every request a connection sends carries its server's bearer token and
/// declared headers, so the bound on a redirect is **that connection's own
/// endpoint** – scheme, host and port – and not the run's allowlist. Being
/// declared somewhere in the deployment's MCP document is not permission to
/// receive *this* server's credential. The allowlist is kept only to tell the
/// operator which of the two refusals they are reading.
///
/// @implNote **Immutable.** Safe to share across threads.
final class EndpointBound {

    private final String serverName;
    private final URI endpoint;
    private final Set<String> allowedHosts;

    /// Creates the bound for one declared server.
    ///
    /// @param serverName the declared server name, for refusal messages, not null
    /// @param endpoint the URL the server was declared at, not null
    /// @param allowedHosts every host declared across the deployment, not null;
    ///     compared case-insensitively
    EndpointBound(String serverName, URI endpoint, Set<String> allowedHosts) {
        this.serverName = serverName;
        this.endpoint = endpoint;
        this.allowedHosts =
                allowedHosts.stream()
                        .map(host -> host.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    /// Resolves a redirect and refuses one that would leave this endpoint.
    ///
    /// @param reply a 3xx answer, not null
    /// @return the absolute target, which is this same endpoint, never null
    /// @throws McpException if the answer names no usable `Location`
    /// @throws McpEgressDeniedException if the target is any other endpoint
    URI follow(HttpReply reply) {
        String location = reply.header("location");
        if (location == null || location.isBlank()) {
            throw new McpException(
                    "MCP server '"
                            + serverName
                            + "' answered HTTP "
                            + reply.status()
                            + " with no Location header");
        }
        URI moved;
        try {
            moved = endpoint.resolve(new URI(location));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new McpException(
                    "MCP server '"
                            + serverName
                            + "' redirected to an unusable location '"
                            + location
                            + "'",
                    e);
        }
        if (!authority(moved).equals(authority(endpoint))) {
            String host = moved.getHost() == null ? "" : moved.getHost().toLowerCase(Locale.ROOT);
            throw new McpEgressDeniedException(
                    "MCP server '"
                            + serverName
                            + "' redirected to "
                            + moved
                            + ", which is not "
                            + authority(endpoint)
                            + ". "
                            + (allowedHosts.contains(host)
                                    ? "That host is declared in mcp.yaml for a different server,"
                                            + " and following the redirect would send this"
                                            + " server's credential and headers to it."
                                    : "That host is declared in mcp.yaml for no server at all;"
                                            + " declared hosts are "
                                            + String.join(", ", allowedHosts)
                                            + "."));
        }
        return moved;
    }

    /// Renders scheme, host and effective port, so a default port compares equal
    /// to the same port written out.
    ///
    /// Host alone is not enough: two servers on one host at different ports is
    /// the ordinary local case, and they are as separate as two hosts are – a
    /// token minted for one is not a token for the other. Nor is a downgrade from
    /// `https` to `http` a redirect this client follows.
    private static String authority(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
        return scheme + "://" + host + ":" + port;
    }
}
