package io.hensu.server.workflow;

import java.io.Serial;

/// A start carried an idempotency key that already belongs to an execution started by a
/// different request.
///
/// Answering with that execution would tell the caller its request ran when it did not;
/// starting a second one would break the key's promise. The request is refused instead,
/// which maps to `422 Unprocessable Content`.
public class IdempotencyKeyReusedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 840346178985680831L;

    /// @param message which key, and the execution it belongs to, not null
    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
