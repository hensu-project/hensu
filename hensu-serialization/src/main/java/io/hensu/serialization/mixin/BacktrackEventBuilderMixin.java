package io.hensu.serialization.mixin;

import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

/// Jackson mixin for `BacktrackEvent.Builder` that configures POJO builder deserialization.
///
/// Registered via `HensuJacksonModule.setupModule()` against `BacktrackEvent.Builder.class`.
/// Sets `withPrefix = ""` so Jackson maps JSON field names directly to builder setter names
/// without a `set` or `with` prefix convention.
///
/// @apiNote The companion mixin {@link BacktrackEventMixin} must also be registered so Jackson
/// knows to use the builder at all.
///
/// @implNote Requires `BacktrackEvent.Builder.class` to be registered for reflection in
/// `NativeImageConfig` in `hensu-server` (all-declared constructors + all public methods).
///
/// @see BacktrackEventMixin
/// @see io.hensu.serialization.HensuJacksonModule
@JsonPOJOBuilder(withPrefix = "")
public abstract class BacktrackEventBuilderMixin {}
