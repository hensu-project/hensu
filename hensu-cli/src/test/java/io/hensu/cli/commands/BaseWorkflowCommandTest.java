package io.hensu.cli.commands;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.BacktrackEvent;
import io.hensu.core.execution.result.BacktrackType;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowMetadata;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/// Base class for CLI command tests with common utilities.
abstract class BaseWorkflowCommandTest {

    protected ByteArrayOutputStream outContent;
    protected ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUpStreams() {
        originalOut = System.out;
        originalErr = System.err;
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    /// Injects a value into a field, searching up the class hierarchy.
    protected void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    /// Creates a test workflow with the specified configuration.
    protected Workflow createTestWorkflow(String name, int agentCount, int nodeCount) {
        Map<String, AgentConfig> agents = new HashMap<>();
        for (int i = 0; i < agentCount; i++) {
            String agentId = "agent-" + i;
            agents.put(
                    agentId,
                    AgentConfig.builder()
                            .id(agentId)
                            .role("Test Agent")
                            .model("test-model")
                            .build());
        }

        Map<String, Node> nodes = new HashMap<>();
        for (int i = 0; i < nodeCount - 1; i++) {
            String nodeId = "node-" + i;
            nodes.put(
                    nodeId,
                    StandardNode.builder()
                            .id(nodeId)
                            .agentId("agent-0")
                            .prompt("Test prompt")
                            .transitionRules(List.of(new SuccessTransition("exit")))
                            .build());
        }
        nodes.put("exit", EndNode.builder().id("exit").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id(name)
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                name, "Test workflow", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("node-0")
                .build();
    }

    /// Creates a simple test workflow with 1 agent and 2 nodes.
    protected Workflow createTestWorkflow(String name) {
        return createTestWorkflow(name, 1, 2);
    }

    /// Creates an execution history with the specified number of steps.
    protected ExecutionHistory createHistoryWithSteps(int stepCount) {
        ExecutionHistory history = new ExecutionHistory();
        for (int i = 0; i < stepCount; i++) {
            history.addStep(
                    ExecutionStep.builder()
                            .nodeId("node-" + i)
                            .result(NodeResult.empty())
                            .timestamp(Instant.now())
                            .build());
        }
        return history;
    }

    /// Creates an execution history with backtrack events.
    protected ExecutionHistory createHistoryWithBacktracks() {
        ExecutionHistory history = new ExecutionHistory();
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("step1")
                        .result(NodeResult.empty())
                        .timestamp(Instant.now())
                        .build());
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("step2")
                        .result(NodeResult.empty())
                        .timestamp(Instant.now())
                        .build());
        history.addBacktrack(
                BacktrackEvent.builder()
                        .from("step2")
                        .to("step1")
                        .reason("Low quality score")
                        .type(BacktrackType.AUTOMATIC)
                        .timestamp(Instant.now())
                        .build());
        return history;
    }

    /// Creates a final state with the given execution history.
    protected HensuState createFinalState(ExecutionHistory history) {
        return new HensuState(new HashMap<>(Map.of()), "test-workflow", "exit", history);
    }

    /// Creates a workflow with an unreachable node for validation tests.
    protected Workflow createWorkflowWithUnreachableNode() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "agent-1",
                AgentConfig.builder().id("agent-1").role("Test Agent").model("test-model").build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("agent-1")
                        .prompt("Test prompt")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());
        // This node is not reachable from start
        nodes.put(
                "orphan-node",
                StandardNode.builder()
                        .id("orphan-node")
                        .agentId("agent-1")
                        .prompt("Orphan prompt")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());

        return Workflow.builder()
                .id("unreachable-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "unreachable-workflow",
                                "Test workflow",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }
}
