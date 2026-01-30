package io.hensu.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.FailureTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutorTest {

    @Mock private AgentRegistry agentRegistry;

    @Mock private RubricEngine rubricEngine;

    @Mock private Agent mockAgent;

    private ExecutorService executorService;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(2);
        ReviewHandler reviewHandler = ReviewHandler.AUTO_APPROVE;
        NodeExecutorRegistry nodeExecutorRegistry = new DefaultNodeExecutorRegistry();

        executor =
                new WorkflowExecutor(
                        nodeExecutorRegistry,
                        agentRegistry,
                        executorService,
                        rubricEngine,
                        reviewHandler);
    }

    @AfterEach
    void tearDown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    @Test
    void shouldExecuteSimpleWorkflowToEnd() throws Exception {
        // Given
        Workflow workflow = createSimpleWorkflow();
        Map<String, Object> context = new HashMap<>();
        context.put("input", "test data");

        AgentResponse successResponse = AgentResponse.success("Agent output");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(successResponse);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void shouldThrowExceptionWhenNodeNotFound() {
        // Given
        Workflow workflow = createWorkflowWithMissingNode();
        Map<String, Object> context = new HashMap<>();

        AgentResponse successResponse = AgentResponse.success("Agent output");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(successResponse);

        // When/Then
        assertThatThrownBy(() -> executor.execute(workflow, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Node not found");
    }

    @Test
    void shouldThrowExceptionWhenAgentNotFound() {
        // Given
        Workflow workflow = createSimpleWorkflow();
        Map<String, Object> context = new HashMap<>();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> executor.execute(workflow, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent not found");
    }

    @Test
    void shouldResolveTemplateVariables() throws Exception {
        // Given
        Workflow workflow = createWorkflowWithTemplatePrompt();
        Map<String, Object> context = new HashMap<>();
        context.put("topic", "artificial intelligence");
        context.put("style", "formal");

        AgentResponse successResponse = AgentResponse.success("Generated content");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(eq("Write about artificial intelligence in formal style"), any()))
                .thenReturn(successResponse);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    void shouldRecordExecutionHistory() throws Exception {
        // Given
        Workflow workflow = createMultiStepWorkflow();
        Map<String, Object> context = new HashMap<>();

        AgentResponse successResponse = AgentResponse.success("Agent output");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(successResponse);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
        assertThat(completed.getFinalState().getHistory().getSteps()).hasSize(2);
    }

    @Test
    void shouldHandleAgentFailureResponse() throws Exception {
        // Given
        Workflow workflow = createWorkflowWithFailureTransition();
        Map<String, Object> context = new HashMap<>();

        AgentResponse failureResponse = AgentResponse.failure(new RuntimeException("Agent error"));
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(failureResponse);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.FAILURE);
    }

    @Test
    void shouldExecuteToFailureEnd() throws Exception {
        // Given
        Workflow workflow = createWorkflowWithFailureEnd();
        Map<String, Object> context = new HashMap<>();

        AgentResponse successResponse = AgentResponse.success("Agent output");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(successResponse);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.FAILURE);
    }

    @Test
    void shouldThrowWhenNoValidTransition() {
        // Given
        Workflow workflow = createWorkflowWithNoTransition();
        Map<String, Object> context = new HashMap<>();

        AgentResponse successResponse = AgentResponse.success("Agent output");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(successResponse);

        // When/Then
        assertThatThrownBy(() -> executor.execute(workflow, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No valid transition");
    }

    @Test
    void shouldHandleEmptyPrompt() throws Exception {
        // Given
        Workflow workflow = createWorkflowWithNullPrompt();
        Map<String, Object> context = new HashMap<>();

        AgentResponse successResponse = AgentResponse.success("Agent output");
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(eq(""), any())).thenReturn(successResponse);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    void shouldExtractParametersFromJsonOutput() throws Exception {
        // Given: Workflow where step1 outputs JSON with parameters, step2 uses them
        Workflow workflow = createWorkflowWithOutputParams();
        Map<String, Object> context = new HashMap<>();

        // Step 1: Returns JSON with extractable parameters
        String jsonOutput =
                """
            Here are the facts:
            {
                "lake_name": "Lake Paravani",
                "peak_height": "5201m",
                "capital": "Tbilisi"
            }
            """;
        AgentResponse step1Response = AgentResponse.success(jsonOutput);

        // Step 2: Uses extracted parameters
        AgentResponse step2Response = AgentResponse.success("Final output using extracted params");

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(step1Response).thenReturn(step2Response);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;

        // Verify parameters were extracted and stored in context
        Map<String, Object> finalContext = completed.getFinalState().getContext();
        assertThat(finalContext.get("lake_name")).isEqualTo("Lake Paravani");
        assertThat(finalContext.get("peak_height")).isEqualTo("5201m");
        assertThat(finalContext.get("capital")).isEqualTo("Tbilisi");
    }

    @Test
    void shouldUseExtractedParamsInSubsequentPrompts() throws Exception {
        // Given: Workflow where step2's prompt uses placeholders from step1's output
        Workflow workflow = createWorkflowWithParamPlaceholders();
        Map<String, Object> context = new HashMap<>();

        // Step 1: Returns JSON with parameters
        String jsonOutput =
                """
            {"country": "Georgia", "language": "Georgian"}
            """;
        AgentResponse step1Response = AgentResponse.success(jsonOutput);
        AgentResponse step2Response = AgentResponse.success("Article about Georgia in Georgian");

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(step1Response).thenReturn(step2Response);

        // When
        ExecutionResult result = executor.execute(workflow, context);

        // Then
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);

        // Verify the second agent call received resolved placeholders
        // Note: We verify by checking the context contains the extracted params
        ExecutionResult.Completed completed = (ExecutionResult.Completed) result;
        assertThat(completed.getFinalState().getContext().get("country")).isEqualTo("Georgia");
        assertThat(completed.getFinalState().getContext().get("language")).isEqualTo("Georgian");
    }

    // Helper methods

    private Workflow createSimpleWorkflow() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("simple-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "simple-workflow", "Test", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithMissingNode() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(List.of(new SuccessTransition("missing-node")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("missing-node-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "missing-node-workflow",
                                "Test",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithTemplatePrompt() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Write about {topic} in {style} style")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("template-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "template-workflow", "Test", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createMultiStepWorkflow() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "step1",
                StandardNode.builder()
                        .id("step1")
                        .agentId("test-agent")
                        .prompt("Step 1")
                        .transitionRules(List.of(new SuccessTransition("step2")))
                        .build());
        nodes.put(
                "step2",
                StandardNode.builder()
                        .id("step2")
                        .agentId("test-agent")
                        .prompt("Step 2")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("multi-step-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "multi-step-workflow", "Test", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("step1")
                .build();
    }

    private Workflow createWorkflowWithFailureTransition() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("success-end"),
                                        new FailureTransition(0, "failure-end")))
                        .build());
        nodes.put(
                "success-end",
                EndNode.builder().id("success-end").status(ExitStatus.SUCCESS).build());
        nodes.put(
                "failure-end",
                EndNode.builder().id("failure-end").status(ExitStatus.FAILURE).build());

        return Workflow.builder()
                .id("failure-transition-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "failure-transition-workflow",
                                "Test",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithFailureEnd() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(List.of(new SuccessTransition("failure-end")))
                        .build());
        nodes.put(
                "failure-end",
                EndNode.builder().id("failure-end").status(ExitStatus.FAILURE).build());

        return Workflow.builder()
                .id("failure-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "failure-workflow", "Test", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithNoTransition() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(List.of()) // No transitions
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("no-transition-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "no-transition-workflow",
                                "Test",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithNullPrompt() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt(null)
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("null-prompt-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "null-prompt-workflow", "Test", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithOutputParams() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "step1",
                StandardNode.builder()
                        .id("step1")
                        .agentId("test-agent")
                        .prompt("Extract facts about Georgia")
                        .outputParams(List.of("lake_name", "peak_height", "capital"))
                        .transitionRules(List.of(new SuccessTransition("step2")))
                        .build());
        nodes.put(
                "step2",
                StandardNode.builder()
                        .id("step2")
                        .agentId("test-agent")
                        .prompt("Use the facts: {lake_name}, {peak_height}, {capital}")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("output-params-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "output-params-workflow",
                                "Test",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("step1")
                .build();
    }

    private Workflow createWorkflowWithParamPlaceholders() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "extract",
                StandardNode.builder()
                        .id("extract")
                        .agentId("test-agent")
                        .prompt("Extract country and language info")
                        .outputParams(List.of("country", "language"))
                        .transitionRules(List.of(new SuccessTransition("use")))
                        .build());
        nodes.put(
                "use",
                StandardNode.builder()
                        .id("use")
                        .agentId("test-agent")
                        .prompt("Write an article about {country} in {language}")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("param-placeholders-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "param-placeholders-workflow",
                                "Test",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("extract")
                .build();
    }
}
