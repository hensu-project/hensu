package io.hensu.serialization.mixin;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.hensu.core.execution.result.BacktrackEvent;

/// Jackson mixin that binds `BacktrackEvent` deserialization to its builder.
///
/// Applied to `BacktrackEvent.class` via `HensuJacksonModule.setupModule()`.
/// Instructs Jackson to use `BacktrackEvent.Builder` when deserializing, enabling
/// immutable construction of review backtrack events from persisted workflow snapshots.
///
/// @apiNote The companion mixin {@link BacktrackEventBuilderMixin} must also be registered
/// so Jackson knows how to invoke the builder's setters and `build()` method.
///
/// @implNote `BacktrackEvent.Builder` has a private constructor. Native-image deployments
/// require `BacktrackEvent.class` and `BacktrackEvent.Builder.class` to be registered in
/// `NativeImageConfig` in `hensu-server`.
///
/// @see BacktrackEventBuilderMixin
/// @see io.hensu.serialization.HensuJacksonModule
@JsonDeserialize(builder = BacktrackEvent.Builder.class)
public abstract class BacktrackEventMixin {}
