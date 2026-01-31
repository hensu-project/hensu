package io.hensu.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.HensuEnvironment;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.dsl.WorkingDirectory;
import io.hensu.dsl.parsers.KotlinScriptParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowRunCommandTest extends BaseWorkflowCommandTest {

    @TempDir Path tempDir;

    @Mock private KotlinScriptParser kotlinParser;

    @Mock private HensuEnvironment environment;

    @Mock private WorkflowExecutor executor;

    private WorkflowRunCommand command;

    @BeforeEach
    void setUp() throws Exception {
        // Create working directory structure
        Files.createDirectories(tempDir.resolve("workflows"));
        Files.createDirectories(tempDir.resolve("prompts"));
        Files.createDirectories(tempDir.resolve("rubrics"));

        command = new WorkflowRunCommand();
        injectField(command, "kotlinParser", kotlinParser);
        injectField(command, "environment", environment);
        injectField(command, "workingDirPath", tempDir);
        lenient().when(environment.getWorkflowExecutor()).thenReturn(executor);
    }

    @Test
    void shouldExecuteKotlinWorkflowSuccessfully() throws Exception {
        // Given
        String workflowName = "test-workflow";
        injectField(command, "workflowName", workflowName);

        Workflow workflow = createTestWorkflow("test-workflow", 2, 3);
        ExecutionHistory history = createHistoryWithSteps(2);
        ExecutionResult.Completed completed =
                new ExecutionResult.Completed(createFinalState(history), ExitStatus.SUCCESS);

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);
        when(executor.execute(eq(workflow), any(), any())).thenReturn(completed);

        // When
        command.run();

        // Then
        verify(kotlinParser).parse(any(WorkingDirectory.class), eq(workflowName));
        verify(executor).execute(eq(workflow), any(), any());

        String output = outContent.toString();
        assertThat(output).contains("Compiling Kotlin DSL workflow");
        assertThat(output).contains("Workflow loaded: test-workflow");
        assertThat(output).contains("Agents: 2");
        assertThat(output).contains("Nodes: 3");
        assertThat(output).contains("Starting workflow execution");
        assertThat(output).contains("Workflow completed successfully");
        assertThat(output).contains("Steps: 2");
    }

    @Test
    void shouldHandleRejectedWorkflow() throws Exception {
        // Given
        String workflowName = "rejected-workflow";
        injectField(command, "workflowName", workflowName);

        Workflow workflow = createTestWorkflow("rejected-workflow", 1, 2);
        ExecutionResult.Rejected rejected =
                new ExecutionResult.Rejected(
                        "Quality score below threshold", createFinalState(new ExecutionHistory()));

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);
        when(executor.execute(eq(workflow), any(), any())).thenReturn(rejected);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("Workflow rejected");
        assertThat(output).contains("Quality score below threshold");
    }

    @Test
    void shouldHandleExecutionException() throws Exception {
        // Given
        String workflowName = "failing-workflow";
        injectField(command, "workflowName", workflowName);

        Workflow workflow = createTestWorkflow("failing-workflow", 1, 2);

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);
        when(executor.execute(eq(workflow), any(), any()))
                .thenThrow(new RuntimeException("Agent unavailable"));

        // When
        command.run();

        // Then
        String errOutput = errContent.toString();
        assertThat(errOutput).contains("Workflow execution failed");
        assertThat(errOutput).contains("Agent unavailable");
    }

    @Test
    void shouldDisplayBacktrackSummaryWhenPresent() throws Exception {
        // Given
        String workflowName = "backtrack-workflow";
        injectField(command, "workflowName", workflowName);

        Workflow workflow = createTestWorkflow("backtrack-workflow", 1, 3);
        ExecutionHistory history = createHistoryWithBacktracks();
        ExecutionResult.Completed completed =
                new ExecutionResult.Completed(createFinalState(history), ExitStatus.SUCCESS);

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);
        when(executor.execute(eq(workflow), any(), any())).thenReturn(completed);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("Backtrack Summary");
        assertThat(output).contains("AUTOMATIC");
        assertThat(output).contains("Low quality score");
    }

    @Test
    void shouldNotDisplayBacktrackSectionWhenNoBacktracks() throws Exception {
        // Given
        String workflowName = "no-backtrack";
        injectField(command, "workflowName", workflowName);

        Workflow workflow = createTestWorkflow("no-backtrack", 1, 2);
        ExecutionHistory history = new ExecutionHistory();
        ExecutionResult.Completed completed =
                new ExecutionResult.Completed(createFinalState(history), ExitStatus.SUCCESS);

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);
        when(executor.execute(eq(workflow), any(), any())).thenReturn(completed);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).doesNotContain("Backtrack Summary");
        assertThat(output).contains("Backtracks: 0");
    }
}
