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
/// ### Only A Redirect That Repeats The Request Is Followed
/// A `307` or `308` tells the client to send the same request elsewhere: the
/// endpoint that answered did not handle it. A `301`, `302` or `303` after a
/// POST is the post-then-redirect pattern, where the endpoint may well have
/// handled the request and is pointing at its result. Following one would send
/// the call a second time, so only the first two are followed, and a refusal
/// says which of the two kinds it was.
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

    /// Resolves a redirect and refuses one that would leave this endpoint or
    /// would repeat a request the endpoint may already have handled.
    ///
    /// The endpoint bound is checked first, so a redirect off this endpoint is
    /// always reported as refused egress, whatever its status.
    ///
    /// @param reply a 3xx answer, not null
    /// @return the absolute target, which is this same endpoint, never null
    /// @throws McpException if the answer names no usable `Location`, or is a
    ///     redirect other than `307` or `308`
    /// @throws McpEgressDeniedException if the target is any other endpoint
    URI follow(HttpReply reply) {
        McpException.Delivery delivery =
                repeatsTheRequest(reply.status())
                        ? McpException.Delivery.NOT_SENT
                        : McpException.Delivery.MAYBE_DELIVERED;
        String location = reply.header("location");
        if (location == null || location.isBlank()) {
            throw new McpException(
                    "MCP server '"
                            + serverName
                            + "' answered HTTP "
                            + reply.status()
                            + " with no Location header",
                    delivery);
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
                    e,
                    delivery);
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
                                            + "."),
                    delivery);
        }
        if (!repeatsTheRequest(reply.status())) {
            throw new McpException(
                    "MCP server '"
                            + serverName
                            + "' answered HTTP "
                            + reply.status()
                            + " to a POST; only 307 and 308 are followed, because any other"
                            + " redirect may come after the server handled the request, and"
                            + " following it would send the call twice",
                    delivery);
        }
        return moved;
    }

    /// Whether a redirect status tells the client to repeat the request unchanged.
    private static boolean repeatsTheRequest(int status) {
        return status == 307 || status == 308;
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
