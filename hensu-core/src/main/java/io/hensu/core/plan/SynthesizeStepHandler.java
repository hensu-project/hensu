package io.hensu.core.plan;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/// {@link StepHandler} that executes {@link PlanStepAction.Synthesize} steps
/// by invoking the node's configured agent.
///
/// The handler builds a synthesis prompt that includes the base instruction
/// from the step description plus any intermediate tool outputs stored in the
/// execution context under {@code _step_N_output} keys, then calls the agent
/// and returns its text response as the step output.
///
/// The {@code agentId} on the action is populated by
/// {@link io.hensu.core.execution.executor.AgenticNodeExecutor} before
/// plan execution begins; this handler trusts it is non-null.
///
/// @implNote Thread-safe. Stateless beyond the injected {@link AgentRegistry}.
///
/// @see ToolCallStepHandler for the tool-call counterpart
/// @see StepHandlerRegistry for registration
public class SynthesizeStepHandler implements StepHandler<PlanStepAction.Synthesize> {

    private final AgentRegistry agentRegistry;

    /// Creates a handler backed by the given agent registry.
    ///
    /// @param agentRegistry registry used to resolve the synthesis agent, not null
    public SynthesizeStepHandler(AgentRegistry agentRegistry) {
        this.agentRegistry =
                Objects.requireNonNull(agentRegistry, "agentRegistry must not be null");
    }

    @Override
    public Class<PlanStepAction.Synthesize> getActionType() {
        return PlanStepAction.Synthesize.class;
    }

    @Override
    public StepResult handle(
            PlannedStep step, PlanStepAction.Synthesize action, Map<String, Object> context) {
        Instant start = Instant.now();

        String agentId = action.agentId();
        if (agentId == null || agentId.isBlank()) {
            return StepResult.failure(
                    step.index(), "synthesize", "Synthesize step has no agentId", Duration.ZERO);
        }

        Agent agent = agentRegistry.getAgent(agentId).orElse(null);
        if (agent == null) {
            return StepResult.failure(
                    step.index(),
                    "synthesize",
                    "Agent not found for synthesis: " + agentId,
                    Duration.ZERO);
        }

        String prompt = buildSynthesisPrompt(action.prompt(), context);
        AgentResponse response = agent.execute(prompt, context);
        Duration duration = Duration.between(start, Instant.now());

        return switch (response) {
            case AgentResponse.TextResponse t ->
                    StepResult.success(step.index(), "synthesize", t.content(), duration);
            case AgentResponse.Error e ->
                    StepResult.failure(step.index(), "synthesize", e.message(), duration);
            case AgentResponse.ToolRequest tr ->
                    StepResult.failure(
                            step.index(),
                            "synthesize",
                            "Unexpected tool request during synthesis: " + tr.toolName(),
                            duration);
            case AgentResponse.PlanProposal _ ->
                    StepResult.failure(
                            step.index(),
                            "synthesize",
                            "Unexpected plan proposal during synthesis",
                            duration);
        };
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /// Builds the full synthesis prompt by appending collected tool outputs
    /// from the execution context.
    ///
    /// @param basePrompt the instruction from the synthesize step
    /// @param context    mutable execution context containing step outputs
    /// @return the augmented prompt string, never null
    private String buildSynthesisPrompt(String basePrompt, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder(basePrompt != null ? basePrompt : "");

        boolean hasOutputs = false;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            if (entry.getKey().startsWith("_step_") && entry.getKey().endsWith("_output")) {
                if (!hasOutputs) {
                    sb.append("\n\n## Collected Tool Results\n");
                    hasOutputs = true;
                }
                sb.append("- ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(entry.getValue())
                        .append("\n");
            }
        }

        return sb.toString();
    }
}
