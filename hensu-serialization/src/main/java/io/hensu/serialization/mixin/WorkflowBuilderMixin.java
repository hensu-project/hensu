package io.hensu.serialization.mixin;

import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

/// Jackson mixin for `Workflow.Builder` that configures POJO builder deserialization.
///
/// Registered via `HensuJacksonModule.setupModule()` against `Workflow.Builder.class`.
/// Sets `withPrefix = ""` so Jackson maps JSON field names directly to builder setter names
/// without a `set` or `with` prefix convention.
///
/// @apiNote The companion mixin {@link WorkflowMixin} must also be registered so Jackson
/// knows to use the builder at all.
///
/// @implNote Requires `Workflow.Builder.class` to be registered for reflection in
/// `NativeImageConfig` in `hensu-server` (all-declared constructors + all public methods).
///
/// @see WorkflowMixin
/// @see io.hensu.serialization.HensuJacksonModule
@JsonPOJOBuilder(withPrefix = "")
public abstract class WorkflowBuilderMixin {}
