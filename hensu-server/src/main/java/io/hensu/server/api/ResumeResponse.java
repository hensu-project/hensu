package io.hensu.server.api;

import io.quarkus.runtime.annotations.RegisterForReflection;

/// Acknowledgment returned after a successful resume request.
///
/// @param status the outcome, always {@code "resumed"}
@RegisterForReflection
record ResumeResponse(String status) {

    static final ResumeResponse RESUMED = new ResumeResponse("resumed");
}
