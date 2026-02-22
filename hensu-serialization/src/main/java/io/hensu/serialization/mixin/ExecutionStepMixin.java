package io.hensu.serialization.mixin;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.hensu.core.execution.result.ExecutionStep;

/// Jackson mixin that binds `ExecutionStep` deserialization to its builder.
///
/// Applied to `ExecutionStep.class` via `HensuJacksonModule.setupModule()`.
/// Instructs Jackson to use `ExecutionStep.Builder` when deserializing, enabling
/// immutable construction of recorded workflow steps from persisted snapshots.
///
/// @apiNote The companion mixin {@link ExecutionStepBuilderMixin} must also be registered
/// so Jackson knows how to invoke the builder's setters and `build()` method.
///
/// @implNote `ExecutionStep.Builder` has a private constructor. Native-image deployments
/// require `ExecutionStep.class` and `ExecutionStep.Builder.class` to be registered in
/// `NativeImageConfig` in `hensu-server`.
///
/// @see ExecutionStepBuilderMixin
/// @see io.hensu.serialization.HensuJacksonModule
@JsonDeserialize(builder = ExecutionStep.Builder.class)
public abstract class ExecutionStepMixin {}
