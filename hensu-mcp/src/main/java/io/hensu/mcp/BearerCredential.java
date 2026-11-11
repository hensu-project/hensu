package io.hensu.mcp;

import java.util.Optional;

/// The bearer token a Streamable HTTP connection sends, and what to send instead
/// once the server refuses it.
///
/// A static string cannot follow a rotation: the connection keeps sending the
/// value it was opened with long after the secret behind it changed. This seam
/// lets the runtime that owns the secret answer the two questions a connection
/// actually has – what to send now, and whether a refused token has a successor
/// – without `hensu-mcp` learning where secrets are kept.
///
/// ### Contracts
/// - **Precondition**: implementations never return a blank token; absence is
///   {@link Optional#empty()}
/// - **Postcondition**: a connection resends a refused request at most once,
///   and only when {@link #afterRejection} names a token different from the
///   one refused
/// - **Invariant**: `toString()` of every implementation in this module names
///   no token, and neither does any exception message the connection builds
///
/// @implNote Implementations are called from every Virtual Thread sharing a
///     connection and must be thread-safe.
/// @see StreamableHttpMcpConnection#open for where a credential is supplied
public interface BearerCredential {

    /// A credential that sends no `Authorization` header.
    BearerCredential NONE = fixed(null);

    /// Returns the token to send on the next request.
    ///
    /// @return the token, or empty when the endpoint gets no `Authorization` header
    Optional<String> current();

    /// Returns a token worth retrying with after the server answered `401`.
    ///
    /// A request refused at authorization never reached the tool, so resending
    /// it once with a newer token cannot run anything twice.
    ///
    /// @param rejected the token the server refused, or null when none was sent
    /// @return a different token to resend with, or empty when there is none
    Optional<String> afterRejection(String rejected);

    /// Creates a credential that always sends the same token.
    ///
    /// @param token the token, or null when the endpoint needs no authentication
    /// @return a credential with no successor for a refused token, never null
    static BearerCredential fixed(String token) {
        return new Fixed(token == null || token.isBlank() ? null : token);
    }

    /// A token that never changes.
    ///
    /// @param token the token, or null for none
    record Fixed(String token) implements BearerCredential {

        @Override
        public Optional<String> current() {
            return Optional.ofNullable(token);
        }

        @Override
        public Optional<String> afterRejection(String rejected) {
            return Optional.empty();
        }

        /// Names whether a token is held, never the token.
        ///
        /// @return a redacted description, never null
        @Override
        public String toString() {
            return token == null ? "BearerCredential[none]" : "BearerCredential[redacted]";
        }
    }
}
