package io.hensu.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.hensu.cli.visualizer.WorkflowVisualizer;
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
class WorkflowVisualizeCommandTest extends BaseWorkflowCommandTest {

    @TempDir Path tempDir;

    @Mock private KotlinScriptParser kotlinParser;

    @Mock private WorkflowVisualizer visualizer;

    private WorkflowVisualizeCommand command;

    @BeforeEach
    void setUp() throws Exception {
        // Create working directory structure
        Files.createDirectories(tempDir.resolve("workflows"));
        Files.createDirectories(tempDir.resolve("prompts"));
        Files.createDirectories(tempDir.resolve("rubrics"));

        command = new WorkflowVisualizeCommand();
        injectField(command, "kotlinParser", kotlinParser);
        injectField(command, "visualizer", visualizer);
        injectField(command, "workingDirPath", tempDir);
    }

    @Test
    void shouldVisualizeWithTextFormat() throws Exception {
        // Given
        String workflowName = "test-workflow";
        injectField(command, "workflowName", workflowName);
        injectField(command, "format", "text");

        Workflow workflow = createTestWorkflow("test-workflow");
        String expectedOutput =
                """
                Workflow: test-workflow
                ==================================================

                ┌─ start (STANDARD)
                │  Agent: agent-1
                │  Transitions:
                │    → end (on success)
                └─
                """;

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);
        when(visualizer.visualize(workflow, "text")).thenReturn(expectedOutput);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("Workflow: test-workflow");
        assertThat(output).contains("start (STANDARD)");
    }

    @Test
    void shouldOutputMermaidFormat() throws Exception {
        // Given
        String workflowName = "mermaid-workflow";
        injectField(command, "workflowName", workflowName);
        injectField(command, "format", "mermaid");

        Workflow workflow = createTestWorkflow("mermaid-workflow");
        String mermaidOutput = "```mermaid\nflowchart TD\n  start --> end\n```\n";

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);
        when(visualizer.visualize(workflow, "mermaid")).thenReturn(mermaidOutput);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("flowchart TD");
    }

    @Test
    void shouldHandleUnsupportedFormat() throws Exception {
        // Given
        String workflowName = "test-workflow";
        injectField(command, "workflowName", workflowName);
        injectField(command, "format", "unknown");

        Workflow workflow = createTestWorkflow("test-workflow");

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);
        when(visualizer.visualize(workflow, "unknown"))
                .thenThrow(new IllegalArgumentException("Unsupported format: unknown"));

        // When
        command.run();

        // Then
        String errOutput = errContent.toString();
        assertThat(errOutput).contains("Unsupported format: unknown");
    }

    @Test
    void shouldAcceptKtsExtension() throws Exception {
        // Given
        String workflowName = "kts-workflow";
        injectField(command, "workflowName", workflowName);
        injectField(command, "format", "text");

        Workflow workflow = createTestWorkflow("kts-workflow");

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);
        when(visualizer.visualize(workflow, "text")).thenReturn("Workflow: kts-workflow");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("Workflow: kts-workflow");
    }
}
