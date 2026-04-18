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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
        injectField(command, "subWorkflowLoader", createSubWorkflowLoader(kotlinParser));
        injectField(command, "environment", environment);
        injectField(command, "workingDirPath", tempDir);
        injectField(command, "noDaemon", true); // force inline — tests run alongside a live daemon
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

        // Then — verify the DSL was compiled and the executor was invoked
        verify(kotlinParser).parse(any(WorkingDirectory.class), eq(workflowName));
        verify(executor).execute(eq(workflow), any(), any());
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

        // Then — executor was invoked and Rejected result was handled without exception
        verify(executor).execute(eq(workflow), any(), any());
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

        // Then — exception is caught internally; executor was called and command did not rethrow
        verify(executor).execute(eq(workflow), any(), any());
    }

    // — parseSimpleJson ———————————————————————————————————————————————————————

    @Test
    void parseSimpleJson_parsesStringNumberAndBoolValues() throws Exception {
        Map<String, Object> result =
                (Map<String, Object>)
                        parseSimpleJson("{\"name\": \"alice\", \"count\": 42, \"verbose\": true}");

        assertThat(result).containsEntry("name", "alice").containsEntry("verbose", true);
        // The simple regex parser may return Integer or Double for unquoted numbers;
        // verify the numeric value, not the Java type
        assertThat(((Number) result.get("count")).intValue()).isEqualTo(42);
    }

    @Test
    void parseSimpleJson_quotedNumericValue_treatedAsString() throws Exception {
        // A quoted "123" must remain a String; the number pattern must not clobber it
        Map<String, Object> result = (Map<String, Object>) parseSimpleJson("{\"key\": \"123\"}");

        assertThat(result.get("key")).isInstanceOf(String.class).isEqualTo("123");
    }

    @Test
    void parseSimpleJson_hyphenatedKey_isDropped() throws Exception {
        // \\w+ in the regex does not match hyphens — documents a known limitation
        // so any future fix will be caught here
        Map<String, Object> result =
                (Map<String, Object>) parseSimpleJson("{\"my-key\": \"value\"}");

        assertThat(result).isEmpty();
    }

    private Object parseSimpleJson(String json) throws Exception {
        Method m = WorkflowRunCommand.class.getDeclaredMethod("parseSimpleJson", String.class);
        m.setAccessible(true);
        return m.invoke(command, json);
    }
}
