package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.workflow.WorkflowTest;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowExecutorTemplateResolutionTest extends WorkflowExecutorTestBase {

    @Test
    void shouldResolveTemplateVariables() throws Exception {
        // Prompt uses {topic} and {style}; executor must substitute from initial context.
        // If substitution fails the mock won't match and the test will fail.
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("start")
                                        .agentId("test-agent")
                                        .prompt("Write about {topic} in {style} style")
                                        .transitionRules(List.of(new SuccessTransition("end")))
                                        .build())
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(eq("Write about artificial intelligence in formal style"), any()))
                .thenReturn(AgentResponse.TextResponse.of("Generated content"));

        var ctx = new HashMap<String, Object>();
        ctx.put("topic", "artificial intelligence");
        ctx.put("style", "formal");
        var result = executor.execute(workflow, ctx);

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    void shouldResolvePreviousNodeOutputAsPlaceholder() throws Exception {
        // step1 output stored under key "step1"; step2 uses {step1} in its prompt.
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("template-chain")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("step1")
                                        .agentId("test-agent")
                                        .prompt("Generate greeting")
                                        .transitionRules(List.of(new SuccessTransition("step2")))
                                        .build())
                        .node(
                                StandardNode.builder()
                                        .id("step2")
                                        .agentId("test-agent")
                                        .prompt("Write about {step1}")
                                        .transitionRules(List.of(new SuccessTransition("end")))
                                        .build())
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Hello World"))
                .thenReturn(AgentResponse.TextResponse.of("Article about Hello World"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getFinalState().getContext().get("step1"))
                .isEqualTo("Hello World");
    }

    @Test
    void shouldSubstituteEmptyStringForUnresolvedVariable() throws Exception {
        // SimpleTemplateResolver contract: missing keys become "" not an error.
        // The strict eq() matcher will fail if the resolver ever starts throwing or
        // substituting a different placeholder (e.g., "{missing_key}") instead of "".
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("missing-key-test")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("start")
                                        .agentId("test-agent")
                                        .prompt("Topic: {topic} Extra: {undefined_key}")
                                        .transitionRules(List.of(new SuccessTransition("end")))
                                        .build())
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        // The resolver must silently produce "" for {undefined_key}, not throw or leave the
        // placeholder literal. If the resolved string changes, the mock won't match and the
        // test fails — exposing the resolver contract change.
        when(mockAgent.execute(eq("Topic: AI Extra: "), any()))
                .thenReturn(AgentResponse.TextResponse.of("result"));

        var ctx = new HashMap<String, Object>();
        ctx.put("topic", "AI");
        var result = executor.execute(workflow, ctx);

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    void shouldResolveMultipleSourcesInOnePrompt() throws Exception {
        // step2 prompt uses both {topic} from initial context and {step1} from previous node.
        // The mock matcher verifies the exact resolved string, catching any resolution bug.
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("multi-source")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("step1")
                                        .agentId("test-agent")
                                        .prompt("Research {topic}")
                                        .transitionRules(List.of(new SuccessTransition("step2")))
                                        .build())
                        .node(
                                StandardNode.builder()
                                        .id("step2")
                                        .agentId("test-agent")
                                        .prompt("Write about {topic} using research: {step1}")
                                        .transitionRules(List.of(new SuccessTransition("end")))
                                        .build())
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(eq("Research AI"), any()))
                .thenReturn(AgentResponse.TextResponse.of("AI research findings"));
        when(mockAgent.execute(eq("Write about AI using research: AI research findings"), any()))
                .thenReturn(AgentResponse.TextResponse.of("Final article"));

        var ctx = new HashMap<String, Object>();
        ctx.put("topic", "AI");
        var result = executor.execute(workflow, ctx);

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getFinalState().getContext().get("step1"))
                .isEqualTo("AI research findings");
    }
}
