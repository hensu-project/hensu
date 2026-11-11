package io.hensu.mcp;

import java.util.Collection;
import java.util.LinkedHashMap;
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
/// the names and numbers, and the messages both transports send and read the
/// same way: the `initialize` request and its answer, a `tools/call`, and a
/// cancellation.
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
    private static Handshake read(Map<String, Object> initializeResult) {
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

    /// Reads an `initialize` result and refuses a revision Hensu does not implement.
    ///
    /// A result that names no revision is refused too: the field is required, and
    /// a server that omits it gives no basis for reading anything it says next.
    /// The refusal is {@link McpException.Delivery#NOT_SENT}, because no tool call
    /// has gone out under the handshake.
    ///
    /// @param serverName the declared name of the server, not null
    /// @param initializeResult the parsed `result` object, not null
    /// @return what the handshake announced, never null; its revision is supported
    /// @throws McpException if the revision is missing or not one of {@link #SUPPORTED}
    static Handshake acceptHandshake(String serverName, Map<String, Object> initializeResult) {
        Handshake announced = read(initializeResult);
        if (!isSupported(announced.revision())) {
            throw new McpException(
                    unsupportedRevision(serverName, announced.revision()),
                    McpException.Delivery.NOT_SENT);
        }
        return announced;
    }

    /// Returns the params of an `initialize` request.
    ///
    /// @return a fresh mutable map announcing {@link #LEGACY_REVISION}, never null
    static Map<String, Object> initializeParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", LEGACY_REVISION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", CLIENT_INFO);
        return params;
    }

    /// Returns the params of a `tools/call` request.
    ///
    /// @param toolName the tool's name as the server publishes it, not null
    /// @param arguments the agent's arguments, may be null (sent as empty)
    /// @param control carries the caller's `_meta`, sent only when non-empty, not null
    /// @return a fresh mutable map, never null
    static Map<String, Object> callParams(
            String toolName, Map<String, Object> arguments, CallControl control) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        if (!control.meta().isEmpty()) {
            params.put("_meta", control.meta());
        }
        return params;
    }

    /// Returns the params of a `notifications/cancelled` notification.
    ///
    /// @param requestId the id of the request the server should drop, not null
    /// @param reason why it was dropped, sent as written, not null
    /// @return a fresh mutable map, never null
    static Map<String, Object> cancellationParams(String requestId, String reason) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", requestId);
        params.put("reason", reason);
        return params;
    }

    /// Builds the notice for a server whose handshake declared no `tools` capability.
    ///
    /// @param serverName the declared name of the server, not null
    /// @return a message saying why the server's catalog is empty, never null
    static String noToolsCapability(String serverName) {
        return "MCP server '"
                + serverName
                + "' advertises no tools capability, so none were requested";
    }

    /// What a server announced when it answered `initialize`.
    ///
    /// @param revision the protocol revision it named, may be null
    /// @param serverName the name it gave itself, may be null
    /// @param declaresTools whether its capabilities include a `tools` entry
    record Handshake(String revision, String serverName, boolean declaresTools) {}

    /// Builds the message for a server whose revision Hensu does not implement.
    ///
    /// @param serverName the declared name of the server, not null
    /// @param serverRevision the revision the server named, may be null
    /// @return a message naming both sides' revisions, never null
    private static String unsupportedRevision(String serverName, String serverRevision) {
        return "MCP server '"
                + serverName
                + "' speaks protocol revision "
                + (serverRevision == null ? "<none announced>" : serverRevision)
                + "; Hensu implements "
                + String.join(", ", SUPPORTED);
    }
}
