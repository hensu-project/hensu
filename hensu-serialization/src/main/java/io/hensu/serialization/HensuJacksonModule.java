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

/// Jackson module for the Hensu workflow type hierarchy.
///
/// Registers custom serializers for types Jackson cannot handle natively:
/// polymorphic nodes, sealed transitions/actions, and builder-pattern classes.
///
/// @implNote GraalVM-safe. All registrations are explicit — no reflective scanning.
/// @see WorkflowSerializer for the convenience API
public class HensuJacksonModule extends SimpleModule {

    @Serial private static final long serialVersionUID = -8700343972457264694L;

    public HensuJacksonModule() {
        super("HensuJacksonModule");

        addSerializer(Node.class, new NodeSerializer());
        addDeserializer(Node.class, new NodeDeserializer());

        addSerializer(TransitionRule.class, new TransitionRuleSerializer());
        addDeserializer(TransitionRule.class, new TransitionRuleDeserializer());

        addSerializer(Action.class, new ActionSerializer());
        addDeserializer(Action.class, new ActionDeserializer());
    }

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
