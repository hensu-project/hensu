package io.hensu.mcp;

import java.io.Serial;

/// Completes a request its caller cancelled, so the waiting thread wakes now.
///
/// Never leaves the transport that raised it: each one catches it on the thread
/// that waited and reports an {@link McpException} classified by how far the
/// request got. Carries no stack trace, because it marks a decision rather than
/// a fault.
final class CancelledByCaller extends RuntimeException {

    @Serial private static final long serialVersionUID = 1584968187374637095L;

    CancelledByCaller() {
        super(null, null, false, false);
    }
}
