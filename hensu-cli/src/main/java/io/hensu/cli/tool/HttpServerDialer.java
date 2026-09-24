package io.hensu.cli.tool;

import io.hensu.cli.daemon.CredentialsStore;
import io.hensu.mcp.McpException;
import io.hensu.mcp.McpServerSpec;
import io.hensu.mcp.StreamableHttpMcpConnection;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Dials the remote MCP servers a run declares, resolving each one's credential first.
///
/// An `auth: {bearer: KEY}` entry names a key in the operator's credential
/// store. It is resolved here and injected into the connection, so `hensu-mcp`
/// never learns that a credential store exists.
///
/// @implNote **Not thread-safe.** Called only from the provider's single
///     launch pass and its shutdown, both under the provider's lock.
final class HttpServerDialer implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(HttpServerDialer.class.getName());

    private final CredentialsStore credentials;
    private final Consumer<String> operator;
    private HttpClient client;

    /// Creates a dialer over the operator's credential store.
    ///
    /// @param credentials where an `auth: {bearer: KEY}` entry is resolved, not null
    /// @param operator receives every reason a server is not dialled, not null
    HttpServerDialer(CredentialsStore credentials, Consumer<String> operator) {
        this.credentials = credentials;
        this.operator = operator;
    }

    /// Dials one remote server.
    ///
    /// A declared key that is not in the store stops the server here, before any
    /// of its tools reach a catalog. That is deliberate: falling through to an
    /// unauthenticated call would turn a configuration mistake into a request
    /// that leaves the machine without the credential the operator meant to send.
    ///
    /// @param spec the declaration, not null
    /// @param allowedHosts every host declared across the run's `mcp.yaml`, not null
    /// @return the route to the server, or empty when it was skipped or did not open
    Optional<McpRoute> dial(McpServerSpec.Http spec, Set<String> allowedHosts) {
        String token = null;
        if (spec.bearerKey() != null) {
            try {
                token = credentials.loadAll().get(spec.bearerKey());
            } catch (IOException e) {
                operator.accept(
                        "MCP server '"
                                + spec.name()
                                + "' is skipped because its credentials could not be read from "
                                + credentials.path()
                                + ": "
                                + e.getMessage());
                return Optional.empty();
            }
            if (token == null || token.isBlank()) {
                operator.accept(
                        "MCP server '"
                                + spec.name()
                                + "' declares auth.bearer '"
                                + spec.bearerKey()
                                + "', which is not set in "
                                + credentials.path()
                                + "; its tools are not offered to agents. Set it with 'hensu"
                                + " credentials set "
                                + spec.bearerKey()
                                + "'.");
                return Optional.empty();
            }
        }
        try {
            return Optional.of(
                    new McpRoute(
                            spec,
                            StreamableHttpMcpConnection.open(spec, token, allowedHosts, client())));
        } catch (McpException e) {
            String message =
                    "MCP server '"
                            + spec.name()
                            + "' at "
                            + spec.url()
                            + " did not open, its tools are not offered to agents: "
                            + e.getMessage();
            logger.log(Level.FINE, message, e);
            operator.accept(message);
            return Optional.empty();
        }
    }

    /// Closes the run's outbound client, if one was built.
    @Override
    public void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    /// Builds the run's one outbound client, on first use.
    private HttpClient client() {
        if (client == null) {
            client = StreamableHttpMcpConnection.defaultClient();
        }
        return client;
    }
}
