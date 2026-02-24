package io.hensu.server.config;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.BacktrackEvent;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanConstraints;
import io.hensu.core.plan.PlanSnapshot;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.plan.PlanningConfig;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.quarkus.runtime.annotations.RegisterForReflection;

/// GraalVM native image reflection registrations for `hensu-core` domain model classes.
///
/// Two patterns require explicit registration:
///
/// ### 1. Jackson `@JsonPOJOBuilder` mixin pattern
/// {@link io.hensu.serialization.HensuJacksonModule} maps each of these types to a builder mixin.
/// At runtime, Jackson must reflectively instantiate the builder (private constructor), call each
/// setter, and invoke `build()`. GraalVM cannot trace these call sites statically.
///
/// - `Workflow` / `Workflow.Builder`
/// - `AgentConfig` / `AgentConfig.Builder`
/// - `ExecutionStep` / `ExecutionStep.Builder`
/// - `NodeResult` / `NodeResult.Builder`
/// - `BacktrackEvent` / `BacktrackEvent.Builder`
/// - `ExecutionHistory`
///
/// ### 2. `treeToValue` delegation in custom deserializers
/// `io.hensu.serialization.NodeDeserializer` delegates `PlanningConfig` and `Plan`
/// deserialization to `mapper.treeToValue()` because their nested `Duration` / `PlannedStep`
/// fields make manual `JsonNode` extraction error-prone. These types must be registered so
/// Jackson's POJO reflection can reach their fields at runtime.
///
/// Simple nested types (`ReviewConfig`, `ConsensusConfig`, `Branch`, `ScoreCondition`,
/// `DoubleRange`) are extracted manually in both serializer and deserializer — no registration
/// needed.
///
/// ### 3. Record types in execution state snapshots
/// `HensuSnapshot` is embedded in `ExecutionStep` (via `ExecutionStep.Builder.snapshot`) and
/// serialized as part of JDBC state persistence. Its nested `PlanSnapshot` record hierarchy
/// (`PlanSnapshot`, `PlannedStepSnapshot`, `StepResultSnapshot`) must also be registered so
/// Jackson can reach the canonical constructors and component accessors at runtime.
///
/// @implNote No Quarkus annotations are placed on `hensu-core` types. All native image metadata
/// lives in `hensu-server`, keeping the core module dependency-free.
/// @see io.hensu.serialization.HensuJacksonModule for the mixin registrations and
///     `treeToValue` delegation sites
@RegisterForReflection(
        targets = {
            // --- Mixin/builder pattern ---
            Workflow.class,
            Workflow.Builder.class,
            AgentConfig.class,
            AgentConfig.Builder.class,
            ExecutionStep.class,
            ExecutionStep.Builder.class,
            NodeResult.class,
            NodeResult.Builder.class,
            BacktrackEvent.class,
            BacktrackEvent.Builder.class,
            ExecutionHistory.class,
            // --- treeToValue delegation (Duration nesting) ---
            PlanningConfig.class,
            PlanConstraints.class,
            Plan.class,
            PlannedStep.class,
            // --- Record types for execution state snapshots ---
            HensuSnapshot.class,
            PlanSnapshot.class,
            PlanSnapshot.PlannedStepSnapshot.class,
            PlanSnapshot.StepResultSnapshot.class
        })
public class NativeImageConfig {}
