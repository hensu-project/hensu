package io.hensu.serialization.mixin;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.hensu.core.agent.AgentConfig;

/// Jackson mixin that binds `AgentConfig` deserialization to its builder.
///
/// Applied to `AgentConfig.class` via `HensuJacksonModule.setupModule()`.
/// Instructs Jackson to use `AgentConfig.Builder` when deserializing, enabling
/// immutable construction without direct field access or a no-arg constructor.
///
/// @apiNote The companion mixin {@link AgentConfigBuilderMixin} must also be registered
/// so Jackson knows how to invoke the builder's setters and `build()` method.
///
/// @implNote `AgentConfig.Builder` has a private constructor. Native-image deployments
/// require `AgentConfig.class` and `AgentConfig.Builder.class` to be registered in
/// `NativeImageConfig` in `hensu-server`.
///
/// @see AgentConfigBuilderMixin
/// @see io.hensu.serialization.HensuJacksonModule
@JsonDeserialize(builder = AgentConfig.Builder.class)
public abstract class AgentConfigMixin {}
