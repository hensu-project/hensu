package io.hensu.server.api;

import io.quarkus.runtime.annotations.RegisterForReflection;

/// Result of pushing a workflow definition.
///
/// @param id the workflow identifier, never null
/// @param version the workflow version, never null
/// @param created {@code true} if newly created, {@code false} if updated
@RegisterForReflection
record PushWorkflowResponse(String id, String version, boolean created) {}
