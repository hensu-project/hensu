package io.hensu.serialization;

import com.fasterxml.jackson.databind.module.SimpleModule;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.BacktrackEvent;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.transition.TransitionRule;
import io.hensu.serialization.mixin.AgentConfigBuilderMixin;
import io.hensu.serialization.mixin.AgentConfigMixin;
import io.hensu.serialization.mixin.BacktrackEventBuilderMixin;
import io.hensu.serialization.mixin.BacktrackEventMixin;
import io.hensu.serialization.mixin.ExecutionHistoryMixin;
import io.hensu.serialization.mixin.ExecutionStepBuilderMixin;
import io.hensu.serialization.mixin.ExecutionStepMixin;
import io.hensu.serialization.mixin.NodeResultBuilderMixin;
import io.hensu.serialization.mixin.NodeResultMixin;
import io.hensu.serialization.mixin.WorkflowBuilderMixin;
import io.hensu.serialization.mixin.WorkflowMixin;
import java.io.Serial;

/// Jackson `SimpleModule` that registers all Hensu serialization configuration in one place.
///
/// Covers two distinct registration strategies:
///
/// **Custom serializer/deserializer pairs** (polymorphic sealed hierarchies — discriminator
/// field drives subtype selection at runtime, no reflection required):
/// - `Node` — `NodeSerializer` / `NodeDeserializer`, discriminator: `"nodeType"`
/// - `TransitionRule` — `TransitionRuleSerializer` / `TransitionRuleDeserializer`,
/// discriminator: `"type"`
/// - `Action` — `ActionSerializer` / `ActionDeserializer`, discriminator: `"type"`
///
/// **Mixin/builder pairs** (immutable builder-pattern domain objects — Jackson uses the builder
/// via reflection; native-image requires corresponding `NativeImageConfig` entries):
/// - `Workflow` + `Workflow.Builder`
/// - `AgentConfig` + `AgentConfig.Builder`
/// - `ExecutionStep` + `ExecutionStep.Builder`
/// - `NodeResult` + `NodeResult.Builder`
/// - `BacktrackEvent` + `BacktrackEvent.Builder`
/// - `ExecutionHistory` (field-visibility mixin — no builder)
///
/// @implNote GraalVM-safe. All registrations are explicit — no classpath scanning.
/// Builder classes registered here require companion entries in `NativeImageConfig`
/// in `hensu-server` for native-image reflection.
/// @see WorkflowSerializer for the convenience factory API
public class HensuJacksonModule extends SimpleModule {

    @Serial private static final long serialVersionUID = -8700343972457264694L;

    /// Constructs the module and registers all custom serializer/deserializer pairs.
    ///
    /// Mixin registrations are deferred to {@link #setupModule} where the `SetupContext`
    /// is available. The order of `addSerializer`/`addDeserializer` calls does not affect
    /// precedence — all three pairs are registered unconditionally.
    public HensuJacksonModule() {
        super("HensuJacksonModule");

        addSerializer(Node.class, new NodeSerializer());
        addDeserializer(Node.class, new NodeDeserializer());

        addSerializer(TransitionRule.class, new TransitionRuleSerializer());
        addDeserializer(TransitionRule.class, new TransitionRuleDeserializer());

        addSerializer(Action.class, new ActionSerializer());
        addDeserializer(Action.class, new ActionDeserializer());
    }

    /// Applies mixin annotations to builder-pattern domain types.
    ///
    /// Called by Jackson when the module is registered with an `ObjectMapper`.
    /// Each `setMixInAnnotations` call wires a domain type to its Jackson binding: the
    /// `*Mixin` class carries `@JsonDeserialize(builder = ...)` on the domain type, and
    /// the `*BuilderMixin` class carries `@JsonPOJOBuilder(withPrefix = "")` on the builder.
    ///
    /// The execution history mixin group (`ExecutionStep`, `NodeResult`, `BacktrackEvent`,
    /// `ExecutionHistory`) is required for JDBC state persistence, where snapshots are
    /// round-tripped through JSON.
    ///
    /// @param context the setup context provided by Jackson, not null
    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);

        context.setMixInAnnotations(Workflow.class, WorkflowMixin.class);
        context.setMixInAnnotations(Workflow.Builder.class, WorkflowBuilderMixin.class);

        context.setMixInAnnotations(AgentConfig.class, AgentConfigMixin.class);
        context.setMixInAnnotations(AgentConfig.Builder.class, AgentConfigBuilderMixin.class);

        // Execution history types — required for JDBC state persistence
        context.setMixInAnnotations(ExecutionStep.class, ExecutionStepMixin.class);
        context.setMixInAnnotations(ExecutionStep.Builder.class, ExecutionStepBuilderMixin.class);

        context.setMixInAnnotations(NodeResult.class, NodeResultMixin.class);
        context.setMixInAnnotations(NodeResult.Builder.class, NodeResultBuilderMixin.class);

        context.setMixInAnnotations(BacktrackEvent.class, BacktrackEventMixin.class);
        context.setMixInAnnotations(BacktrackEvent.Builder.class, BacktrackEventBuilderMixin.class);

        context.setMixInAnnotations(ExecutionHistory.class, ExecutionHistoryMixin.class);
    }
}
