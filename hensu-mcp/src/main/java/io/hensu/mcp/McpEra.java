package io.hensu.mcp;

import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/// Everything that differs between the two MCP revisions a Streamable HTTP
/// client has to speak.
///
/// A connection settles on one instance when it opens and keeps it, because era
/// is a property of the server rather than of a request. Making it a sealed
/// hierarchy rather than a boolean means the compiler, not a reviewer, checks
/// that every difference is handled: adding a future revision is a new permitted
/// type, not a new branch in five methods.
///
/// ### Permitted Subtypes
/// - {@link Modern} – revision 2026-07-28: no handshake, no session, identity in
///   `_meta`, annotated parameters mirrored into headers, cancellation by closing
///   the response stream
/// - {@link Legacy} – revision 2024-11-05: `initialize` handshake, an optional
///   session identifier echoed on every request, cancellation by notification
///
/// @implNote **Immutable.** Both implementations are stateless records; the
///     mutable session identifier lives on the connection, not here.
/// @see StreamableHttpMcpConnection for the client that holds one
/// @see McpProtocol for the revision names and header spellings
sealed interface McpEra permits McpEra.Modern, McpEra.Legacy {

    /// The revision this era speaks.
    ///
    /// @return the revision string, never null
    String revision();

    /// Adds the headers this era requires to an outgoing POST.
    ///
    /// @param request the request under construction, not null
    /// @param method the JSON-RPC method being sent, not null
    /// @param targetName the `tools/call` target, or null for other methods
    /// @param sessionId the session identifier currently held, or null
    /// @param mirrored the `Mcp-Param-*` headers the call's arguments require,
    ///     already encoded, not null (empty for every request but `tools/call`)
    void applyHeaders(
            HttpRequest.Builder request,
            String method,
            String targetName,
            String sessionId,
            Map<String, String> mirrored);

    /// Returns whether this era honours `x-mcp-header` parameter annotations.
    ///
    /// Where it does, a tool whose annotations break the spec's constraints is
    /// left out of the catalog, and every annotated argument is repeated in a
    /// header. Where it does not, annotations are ignored entirely.
    ///
    /// @return true when tool parameters are mirrored into headers
    boolean mirrorsParameters();

    /// Returns the params object the request body should carry.
    ///
    /// @param declared the params the caller asked for, not null
    /// @return the params to serialise, never null
    Map<String, Object> params(Map<String, Object> declared);

    /// Returns whether this era mints and echoes a session identifier.
    ///
    /// @return true when a `Mcp-Session-Id` is part of the contract
    boolean sessionScoped();

    /// Cancels a request whose budget expired.
    ///
    /// The response stream is closed by the caller either way. What differs is
    /// whether that closure is itself the cancellation signal.
    ///
    /// @param requestId the identifier of the request that timed out, not null
    /// @param cancellation the best-effort notifier the connection supplies, not null
    void cancel(String requestId, Cancellation cancellation);

    /// How a connection announces that nobody wants an answer any more.
    @FunctionalInterface
    interface Cancellation {

        /// Sends `notifications/cancelled` for one request, best effort.
        ///
        /// @param requestId the request being abandoned, not null
        void notifyCancelled(String requestId);
    }

    /// Revision 2026-07-28: stateless requests carrying their own identity.
    record Modern() implements McpEra {

        @Override
        public String revision() {
            return McpProtocol.MODERN_REVISION;
        }

        @Override
        public void applyHeaders(
                HttpRequest.Builder request,
                String method,
                String targetName,
                String sessionId,
                Map<String, String> mirrored) {
            request.header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.MODERN_REVISION);
            request.header(McpProtocol.HEADER_METHOD, method);
            if (targetName != null) {
                // A header that disagrees with the body is -32020 on the wire, so
                // this value is taken from the same string the body is built from,
                // and encoded the way the server decodes it before comparing.
                request.header(McpProtocol.HEADER_NAME, McpParameterHeaders.encode(targetName));
            }
            // setHeader rather than header: the mirrored value is the one the
            // server checks against the body, so nothing else may stand beside it.
            mirrored.forEach(request::setHeader);
        }

        /// Returns true: revision 2026-07-28 makes mirroring a client **must**.
        ///
        /// @return true
        @Override
        public boolean mirrorsParameters() {
            return true;
        }

        /// Injects the client identity this revision moved out of the handshake.
        ///
        /// @param declared the params the caller asked for, not null
        /// @return the params plus `_meta`, never null
        @Override
        public Map<String, Object> params(Map<String, Object> declared) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.MODERN_REVISION);
            meta.put(McpProtocol.META_CLIENT_INFO, McpProtocol.CLIENT_INFO);
            meta.put(McpProtocol.META_CLIENT_CAPABILITIES, Map.of());
            Map<String, Object> params = new LinkedHashMap<>(declared);
            params.put("_meta", meta);
            return params;
        }

        @Override
        public boolean sessionScoped() {
            return false;
        }

        /// Does nothing: closing the response stream is the cancellation.
        ///
        /// The spec is explicit that a server must read a closed SSE response
        /// stream as cancellation of that request, so a second request would
        /// spend a second budget to say what the first already said.
        ///
        /// @param requestId the request being abandoned, not null and unused
        /// @param cancellation the notifier, not null and unused
        @Override
        public void cancel(String requestId, Cancellation cancellation) {
            // Intentionally empty – see the method contract.
        }
    }

    /// Revision 2024-11-05: a handshake, a session, and explicit cancellation.
    record Legacy() implements McpEra {

        @Override
        public String revision() {
            return McpProtocol.LEGACY_REVISION;
        }

        @Override
        public void applyHeaders(
                HttpRequest.Builder request,
                String method,
                String targetName,
                String sessionId,
                Map<String, String> mirrored) {
            if (sessionId != null) {
                request.header(McpProtocol.HEADER_SESSION_ID, sessionId);
            }
        }

        /// Returns false: the annotation postdates this revision, and a server
        /// speaking it validates no `Mcp-Param-*` header.
        ///
        /// @return false
        @Override
        public boolean mirrorsParameters() {
            return false;
        }

        @Override
        public Map<String, Object> params(Map<String, Object> declared) {
            return declared;
        }

        @Override
        public boolean sessionScoped() {
            return true;
        }

        @Override
        public void cancel(String requestId, Cancellation cancellation) {
            cancellation.notifyCancelled(requestId);
        }
    }
}
