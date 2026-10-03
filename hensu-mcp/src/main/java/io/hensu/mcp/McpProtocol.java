package io.hensu.mcp;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// The MCP revisions Hensu speaks, and the vocabulary that goes with them.
///
/// One place answers "what does Hensu implement", because two transports now
/// have to agree on it: the stdio client announces a revision in its
/// `initialize` handshake, and the Streamable HTTP client announces one in a
/// header on every request. A revision added here reaches both.
///
/// ### Two Eras, Not A Ladder
/// Revision 2026-07-28 removed `initialize`, `notifications/initialized` and
/// session identifiers, and moved client identity into each request's `_meta`.
/// Everything before it did the opposite. Those are the two eras a client has to
/// be able to speak, and {@link McpEra} owns the differences. This class owns
/// only the names and numbers.
///
/// @implNote **Immutable.** Constants and pure functions; safe to use from any thread.
/// @see McpEra for what actually differs between the two revisions
public final class McpProtocol {

    /// Revision that dropped the handshake and moved identity into `_meta`.
    public static final String MODERN_REVISION = "2026-07-28";

    /// Revision Hensu announces to a server that still expects `initialize`.
    public static final String LEGACY_REVISION = "2024-11-05";

    /// Every revision Hensu implements, newest first.
    public static final List<String> SUPPORTED = List.of(MODERN_REVISION, LEGACY_REVISION);

    /// Client identity sent in the handshake and in every modern request's `_meta`.
    public static final Map<String, Object> CLIENT_INFO = Map.of("name", "hensu", "version", "1");

    /// `_meta` key carrying the revision a modern request is written against.
    public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

    /// `_meta` key carrying the client's identity on a modern request.
    public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

    /// `_meta` key carrying the client's capabilities on a modern request.
    public static final String META_CLIENT_CAPABILITIES =
            "io.modelcontextprotocol/clientCapabilities";

    /// `_meta` key a modern result carries the server's identity under.
    public static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

    /// Header naming the revision a modern request is written against.
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

    /// Header repeating the JSON-RPC method of a modern request.
    public static final String HEADER_METHOD = "Mcp-Method";

    /// Header repeating the target name of a modern `tools/call`.
    public static final String HEADER_NAME = "Mcp-Name";

    /// Header carrying a legacy server's session identifier.
    public static final String HEADER_SESSION_ID = "Mcp-Session-Id";

    /// JSON-RPC code for a header that disagrees with the body.
    public static final int HEADER_MISMATCH = -32020;

    /// JSON-RPC code for a revision the server does not implement.
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    /// JSON-RPC code for a method the server does not implement.
    public static final int METHOD_NOT_FOUND = -32601;

    private McpProtocol() {
        // Utility class
    }

    /// Returns whether Hensu implements the given revision.
    ///
    /// @param revision the revision a server named, may be null
    /// @return true when it is one of {@link #SUPPORTED}
    public static boolean isSupported(String revision) {
        return revision != null && SUPPORTED.contains(revision);
    }

    /// Returns the newest revision both sides implement.
    ///
    /// @param offered the revisions the server listed, may be null
    /// @return the newest common revision, or empty when there is none
    public static Optional<String> newestCommon(Collection<?> offered) {
        if (offered == null) {
            return Optional.empty();
        }
        return SUPPORTED.stream().filter(offered::contains).findFirst();
    }

    /// Reads the three things an `initialize` result carries that a client has
    /// to act on.
    ///
    /// Both transports need this the moment there are two of them: a revision
    /// disagreement has to fail the connection rather than surface later as a
    /// confusing method error, the server's identity belongs in the frame an
    /// operator approves, and whether the server has tools at all decides
    /// whether `tools/list` is worth sending.
    ///
    /// @param initializeResult the parsed `result` object, not null
    /// @return what the handshake announced, never null
    public static Handshake read(Map<String, Object> initializeResult) {
        String revision =
                initializeResult.get("protocolVersion") instanceof String named ? named : null;
        String serverName =
                initializeResult.get("serverInfo") instanceof Map<?, ?> info
                                && info.get("name") instanceof String named
                        ? named
                        : null;
        boolean declaresTools =
                initializeResult.get("capabilities") instanceof Map<?, ?> capabilities
                        && capabilities.containsKey("tools");
        return new Handshake(revision, serverName, declaresTools);
    }

    /// What a server announced when it answered `initialize`.
    ///
    /// @param revision the protocol revision it named, may be null
    /// @param serverName the name it gave itself, may be null
    /// @param declaresTools whether its capabilities include a `tools` entry
    public record Handshake(String revision, String serverName, boolean declaresTools) {}

    /// Builds the message for a server whose revision Hensu does not implement.
    ///
    /// @param serverName the declared name of the server, not null
    /// @param serverRevision the revision the server named, may be null
    /// @return a message naming both sides' revisions, never null
    public static String unsupportedRevision(String serverName, String serverRevision) {
        return "MCP server '"
                + serverName
                + "' speaks protocol revision "
                + (serverRevision == null ? "<none announced>" : serverRevision)
                + "; Hensu implements "
                + String.join(", ", SUPPORTED);
    }
}
