package io.hensu.mcp;

import io.hensu.core.execution.action.SandboxPolicy;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// One MCP server, as a deployment declared it.
///
/// A declaration says where a server is and what it is allowed to cost, and the
/// two shapes it can take differ in the one way that matters to everything
/// downstream: whether Hensu owns the process. A {@link Stdio} server is
/// launched here and contained here; an {@link Http} server is somebody else's
/// process on somebody else's host, which no sandbox of ours can reach.
///
/// ### Permitted Subtypes
/// - {@link Stdio} – a process this run launches, contains and kills
/// - {@link Http} – a remote endpoint this run dials out to over Streamable HTTP
///
/// ### What Is Shared And Why
/// `name`, `prefix`, the two timeouts and the two policy flags sit on the
/// interface because none of them is a property of the transport. A tool-name
/// collision happens between catalogs, not between transports. A budget is what
/// an operator is willing to wait, wherever the answer comes from. And
/// `unattended` × `approvalRequired` is one trust decision about one server, as
/// it is for a command entry.
///
/// ### Coming Up And Answering Are Two Different Waits
/// A server's first reply costs whatever its runtime costs to boot: `npx`
/// resolves a package, a JVM or a Python interpreter starts, a TLS handshake and
/// an era probe complete. That is seconds, once. A request against a server
/// already reachable is milliseconds, repeatedly. Charging both to one budget
/// forces an operator to pick a number that is either too slack to catch a
/// wedged server or too tight for the server to ever start, so the two waits are
/// declared apart: `startupTimeoutMs` covers reaching the server and agreeing
/// how to talk to it, `requestTimeoutMs` covers every call after that.
///
/// ### Policy Is Per Server, Not Per Tool
/// `unattended` and `approvalRequired` mirror the same flags on a command entry,
/// and they apply to every tool the server exposes. An operator who is willing
/// to launch or dial a server is willing to let it answer, and per-tool grain
/// would ask them to reason about a catalog they do not control and that can
/// change when the server updates.
///
/// @see StdioMcpConnection for the client that launches a {@link Stdio} server
/// @see StreamableHttpMcpConnection for the client that dials an {@link Http} one
public sealed interface McpServerSpec permits McpServerSpec.Stdio, McpServerSpec.Http {

    /// Wall clock a launch gets when the declaration names none.
    ///
    /// Long enough for a package runner to resolve from a cold cache, an
    /// interpreter to start, or a TLS handshake and era probe to complete; short
    /// enough that a server which will never answer fails the run rather than
    /// hanging it.
    long DEFAULT_STARTUP_TIMEOUT_MS = 30_000L;

    /// Wall clock a request gets when the declaration names none.
    ///
    /// Long enough for a server that has to reach a network service, short
    /// enough that a wedged server fails the node rather than the run.
    long DEFAULT_REQUEST_TIMEOUT_MS = 30_000L;

    /// Returns the identifier the deployment gave this server.
    ///
    /// @return the server name, never null or blank
    String name();

    /// Returns the string prepended to every tool name this server publishes.
    ///
    /// A prefix is how two servers that both publish `search` stay both usable.
    /// It is applied verbatim, so a declaration of `acme_` yields `acme_search`.
    ///
    /// @return the prefix, never null; empty when the declaration names none
    String prefix();

    /// Returns the wall clock allowed for reaching the server and agreeing how
    /// to talk to it – a launch and handshake for stdio, a connect and era probe
    /// for HTTP.
    ///
    /// @return the budget in milliseconds, always positive
    long startupTimeoutMs();

    /// Returns the wall clock allowed for one request against a reachable server.
    ///
    /// @return the budget in milliseconds, always positive
    long requestTimeoutMs();

    /// Returns whether this server may answer in a run with no human present.
    ///
    /// @return true when the deployment marked the server unattended-safe
    boolean unattended();

    /// Returns whether every call needs a reviewer's consent first.
    ///
    /// @return true when the deployment demanded per-call approval
    boolean approvalRequired();

    /// A server process this run launches, contains and kills.
    ///
    /// The declaration carries everything a launch needs: the argv, the
    /// environment the process may add to the hermetic base, and the containment
    /// it runs under.
    ///
    /// @param name the identifier the deployment gave this server, not null or blank
    /// @param prefix prepended to every published tool name, not null (may be empty)
    /// @param command the argv to launch, not null and not empty, already resolved
    /// @param env variables added to the hermetic base, not null (may be empty)
    /// @param sandbox the containment the launch runs under, not null
    /// @param startupTimeoutMs wall clock for the launch and `initialize`
    ///     handshake, measured from the moment the process is forked, positive
    /// @param requestTimeoutMs wall clock for one JSON-RPC request against a
    ///     server that has already completed its handshake, positive
    /// @param unattended whether the server may answer in a run with no human present
    /// @param approvalRequired whether every call needs a reviewer's consent first
    /// @see io.hensu.core.execution.action.HermeticEnvironment for the base environment
    record Stdio(
            String name,
            String prefix,
            List<String> command,
            Map<String, String> env,
            SandboxPolicy sandbox,
            long startupTimeoutMs,
            long requestTimeoutMs,
            boolean unattended,
            boolean approvalRequired)
            implements McpServerSpec {

        /// Compact constructor taking defensive copies and validating the launch.
        public Stdio {
            name = requireName(name);
            prefix = prefix != null ? prefix : "";
            if (command == null || command.isEmpty()) {
                throw new IllegalArgumentException("server '" + name + "' declares no command");
            }
            requireBudgets(name, startupTimeoutMs, requestTimeoutMs);
            command = List.copyOf(command);
            env = env != null ? Map.copyOf(env) : Map.of();
            sandbox = sandbox != null ? sandbox : SandboxPolicy.restrictive();
        }

        /// Creates a stdio declaration with the default timeouts and closed policy.
        ///
        /// @param name the identifier for this server, not null or blank
        /// @param command the argv to launch, not null and not empty
        /// @return the declaration, never null
        public static Stdio of(String name, List<String> command) {
            return new Stdio(
                    name,
                    "",
                    command,
                    Map.of(),
                    SandboxPolicy.restrictive(),
                    DEFAULT_STARTUP_TIMEOUT_MS,
                    DEFAULT_REQUEST_TIMEOUT_MS,
                    false,
                    false);
        }
    }

    /// A remote MCP endpoint this run dials out to over Streamable HTTP.
    ///
    /// There is no process to contain, which is why a declaration of this shape
    /// refuses a `sandbox:` or `env:` block rather than ignoring one: accepting
    /// either would imply a containment Hensu cannot provide. The bound that
    /// does exist is the set of hosts declared across the document, enforced by
    /// the connection when a redirect tries to leave it.
    ///
    /// ### The Credential Is A Key, Never A Value
    /// `bearerKey` names an entry in the operator's credential store. The value
    /// is resolved at launch, by the runtime that owns the store, and injected
    /// into the connection. A token never reaches this record, so it cannot
    /// reach a log line, an audit row or an approval frame through it.
    ///
    /// @param name the identifier the deployment gave this server, not null or blank
    /// @param prefix prepended to every published tool name, not null (may be empty)
    /// @param url the absolute endpoint every request is posted to, not null
    /// @param bearerKey the credential store key holding the bearer token, may be
    ///     null when the endpoint needs no authentication
    /// @param headers extra headers sent on every request, not null (may be empty)
    /// @param startupTimeoutMs wall clock for connecting and settling which
    ///     protocol revision the server speaks, positive
    /// @param requestTimeoutMs wall clock for one JSON-RPC request against a
    ///     server whose era is already known, positive
    /// @param unattended whether the server may answer in a run with no human present
    /// @param approvalRequired whether every call needs a reviewer's consent first
    record Http(
            String name,
            String prefix,
            URI url,
            String bearerKey,
            Map<String, String> headers,
            long startupTimeoutMs,
            long requestTimeoutMs,
            boolean unattended,
            boolean approvalRequired)
            implements McpServerSpec {

        /// Compact constructor taking defensive copies and validating the endpoint.
        public Http {
            name = requireName(name);
            prefix = prefix != null ? prefix : "";
            Objects.requireNonNull(url, "url must not be null");
            if (!url.isAbsolute() || url.getHost() == null) {
                throw new IllegalArgumentException(
                        "server '" + name + "' declares a non-absolute url: " + url);
            }
            requireBudgets(name, startupTimeoutMs, requestTimeoutMs);
            // Insertion order is kept so the bytes on the wire are deterministic,
            // which a Map.copyOf would not guarantee.
            headers =
                    headers != null
                            ? Collections.unmodifiableMap(new LinkedHashMap<>(headers))
                            : Map.of();
        }

        /// Creates an HTTP declaration with the default timeouts and closed policy.
        ///
        /// @param name the identifier for this server, not null or blank
        /// @param url the absolute endpoint, not null
        /// @return the declaration, never null
        public static Http of(String name, URI url) {
            return new Http(
                    name,
                    "",
                    url,
                    null,
                    Map.of(),
                    DEFAULT_STARTUP_TIMEOUT_MS,
                    DEFAULT_REQUEST_TIMEOUT_MS,
                    false,
                    false);
        }

        /// Returns the host every request to this server reaches.
        ///
        /// @return the lower-cased host, never null
        public String host() {
            return url.getHost().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("server name must not be blank");
        }
        return name;
    }

    private static void requireBudgets(String name, long startupTimeoutMs, long requestTimeoutMs) {
        if (startupTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "server '"
                            + name
                            + "' declares a non-positive startup timeout: "
                            + startupTimeoutMs);
        }
        if (requestTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "server '" + name + "' declares a non-positive timeout: " + requestTimeoutMs);
        }
    }
}
