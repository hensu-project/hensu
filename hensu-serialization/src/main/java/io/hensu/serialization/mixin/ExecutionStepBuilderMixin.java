package io.hensu.serialization.mixin;

import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

/// Jackson mixin for `ExecutionStep.Builder` that configures POJO builder deserialization.
///
/// Registered via `HensuJacksonModule.setupModule()` against `ExecutionStep.Builder.class`.
/// Sets `withPrefix = ""` so Jackson maps JSON field names directly to builder setter names
/// without a `set` or `with` prefix convention.
///
/// @apiNote The companion mixin {@link ExecutionStepMixin} must also be registered so Jackson
/// knows to use the builder at all.
///
/// @implNote Requires `ExecutionStep.Builder.class` to be registered for reflection in
/// `NativeImageConfig` in `hensu-server` (all-declared constructors + all public methods).
///
/// @see ExecutionStepMixin
/// @see io.hensu.serialization.HensuJacksonModule
@JsonPOJOBuilder(withPrefix = "")
public abstract class ExecutionStepBuilderMixin {}
