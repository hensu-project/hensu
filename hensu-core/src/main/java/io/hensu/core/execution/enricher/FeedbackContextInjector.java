package io.hensu.core.execution.enricher;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.workflow.node.Node;

/// Injects previous engine feedback into the prompt when the state context
/// carries a non-blank {@link EngineVariables#RECOMMENDATION} value.
///
/// Unlike the other injectors in the pipeline, this one surfaces feedback
/// from a prior evaluation pass rather than instructing the agent to
/// *produce* a new engine variable. It runs first in
/// {@link EngineVariablePromptEnricher#DEFAULT} so the agent sees feedback
/// context before any output-format requirements.
///
/// ### Lifecycle
/// The recommendation value is produced by one node (via output extraction or
/// consensus feedback) and survives into the next node on two paths:
/// backtrack transitions (bounded, under budget) and forward transitions
/// whose rule returns {@link io.hensu.core.workflow.transition.TransitionRule#withFeedback()}
/// {@code true}. {@link io.hensu.core.execution.pipeline.TransitionPostProcessor}
/// owns the cleanup decision: forward transitions without feedback clear all
/// engine vars; backtracks and feedback-enabled forwards preserve
/// recommendation so this injector can surface it.
///
/// This injector is read-only — it never mutates the state context. Cleanup
/// responsibility belongs exclusively to {@code TransitionPostProcessor}.
///
/// ### Append format
/// ```
/// <original prompt>
///
/// ### Previous Feedback
/// <recommendation text>
/// ```
///
/// @implNote **No instance state.** Stateless and read-only; safe to share
/// across Virtual Threads.
///
/// @see EngineVariablePromptEnricher
/// @see io.hensu.core.execution.pipeline.TransitionPostProcessor for engine var lifecycle
/// @see io.hensu.core.execution.pipeline.RubricPostProcessor for rubric feedback
public final class FeedbackContextInjector implements EngineVariableInjector {

    static final String FEEDBACK_SECTION_PREFIX = "\n\n### Previous Feedback\n";

    @Override
    public String inject(String prompt, Node node, ExecutionContext ctx) {
        Object value = ctx.getState().getContext().get(EngineVariables.RECOMMENDATION);
        if (value instanceof String s && !s.isBlank()) {
            return prompt + FEEDBACK_SECTION_PREFIX + s;
        }
        return prompt;
    }
}
