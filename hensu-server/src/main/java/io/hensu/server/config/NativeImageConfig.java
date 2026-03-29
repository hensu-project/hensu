package io.hensu.server.config;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.parallel.Branch;
import io.hensu.core.execution.parallel.ConsensusConfig;
import io.hensu.core.execution.parallel.ConsensusResult;
import io.hensu.core.execution.parallel.ConsensusStrategy;
import io.hensu.core.execution.result.BacktrackEvent;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlanConstraints;
import io.hensu.core.plan.PlanSnapshot;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.plan.PlanningConfig;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.rubric.model.DoubleRange;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.state.StateVariableDeclaration;
import io.hensu.core.workflow.state.WorkflowStateSchema;
import io.quarkus.runtime.annotations.RegisterForReflection;

/// GraalVM native image reflection registrations for `hensu-core` domain model classes.
///
/// Five patterns require explicit registration:
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
/// ### 3. Nested types deserialized manually but serialized by default Jackson
/// `NodeDeserializer` extracts `ConsensusConfig`, `Branch`, `ScoreCondition`, and
/// `DoubleRange` manually via `JsonNode` – no reflection needed for deserialization.
/// However, `WorkflowSerializer.toJson()` uses Jackson's default `BeanSerializer` for
/// the entire `Workflow` graph, which reads record component accessors reflectively.
/// All types reachable from `Workflow` must be registered for serialization to work.
///
/// ### 4. Serialization of simple immutable types
/// `WorkflowStateSchema` uses a custom deserializer (`WorkflowStateSchemaDeserializer`) that
/// extracts fields manually — no reflection required for deserialization. However, Jackson's
/// default **serializer** still reads `getVariables()` reflectively, so both classes must be
/// registered.
///
/// - `WorkflowStateSchema` — typed schema embedded in `Workflow`; serialized via `getVariables()`
/// - `StateVariableDeclaration` — plain record; component accessors called during serialization
///
/// ### 5. Plain records (canonical constructor + component accessors)
/// Records expose state via canonical constructors and component accessors. GraalVM cannot
/// trace these statically when Jackson uses default POJO machinery. Affected types:
///
/// - `ReviewConfig` — embedded in workflow nodes via `AgentConfig`
/// - `HensuSnapshot`, `PlanSnapshot` hierarchy — embedded in `ExecutionStep` for JDBC persistence
///
/// @implNote No Quarkus annotations are placed on `hensu-core` types. All native image metadata
/// lives in `hensu-server`, keeping the core module dependency-free. LangChain4j transport and
/// provider-specific registrations are in dedicated classes.
/// @see LangChain4jNativeConfig for shared LangChain4j transport registrations
/// @see LangChain4jGeminiNativeConfig for Google AI Gemini DTO registrations
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
            // --- Nested types: manual deser, default Jackson ser ---
            Branch.class,
            ConsensusConfig.class,
            ConsensusStrategy.class,
            ConsensusResult.class,
            ConsensusResult.Vote.class,
            ConsensusResult.VoteType.class,
            ScoreCondition.class,
            DoubleRange.class,
            // --- Serialization of simple immutable types ---
            WorkflowStateSchema.class,
            StateVariableDeclaration.class,
            // --- Plain records (canonical constructor + component accessors) ---
            ReviewConfig.class,
            HensuSnapshot.class,
            PlanSnapshot.class,
            PlanSnapshot.PlannedStepSnapshot.class,
            PlanSnapshot.StepResultSnapshot.class
        })
public class NativeImageConfig {}
