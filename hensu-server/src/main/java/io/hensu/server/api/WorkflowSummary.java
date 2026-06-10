package io.hensu.server.api;

import io.quarkus.runtime.annotations.RegisterForReflection;

/// Lightweight workflow listing entry.
///
/// @param id the workflow identifier, never null
/// @param version the workflow version, never null
@RegisterForReflection
record WorkflowSummary(String id, String version) {}
