package io.hensu.serialization.mixin;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.hensu.core.workflow.Workflow;

/// Jackson mixin that binds `Workflow` deserialization to its builder.
///
/// Applied to `Workflow.class` via `HensuJacksonModule.setupModule()`.
/// Instructs Jackson to use `Workflow.Builder` when deserializing, enabling
/// immutable construction without direct field access or a no-arg constructor.
///
/// @apiNote The companion mixin {@link WorkflowBuilderMixin} must also be registered
/// so Jackson knows how to invoke the builder's setters and `build()` method.
///
/// @implNote `Workflow.Builder` has a private constructor. Native-image deployments
/// require `Workflow.class` and `Workflow.Builder.class` to be registered in
/// `NativeImageConfig` in `hensu-server`.
///
/// @see WorkflowBuilderMixin
/// @see io.hensu.serialization.HensuJacksonModule
@JsonDeserialize(builder = Workflow.Builder.class)
public abstract class WorkflowMixin {}
