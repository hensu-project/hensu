package io.hensu.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
class WorkflowValidateCommandTest extends BaseWorkflowCommandTest {

    @TempDir Path tempDir;

    @Mock private KotlinScriptParser kotlinParser;

    private WorkflowValidateCommand command;

    @BeforeEach
    void setUp() throws Exception {
        // Create working directory structure
        Files.createDirectories(tempDir.resolve("workflows"));
        Files.createDirectories(tempDir.resolve("prompts"));
        Files.createDirectories(tempDir.resolve("rubrics"));

        command = new WorkflowValidateCommand();
        injectField(command, "kotlinParser", kotlinParser);
        injectField(command, "workingDirPath", tempDir);
    }

    @Test
    void shouldValidateWorkflowSuccessfully() throws Exception {
        // Given
        String workflowName = "valid-workflow";
        injectField(command, "workflowName", workflowName);

        Workflow workflow = createTestWorkflow("valid-workflow");

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("Workflow is valid");
        assertThat(output).contains("Name: valid-workflow");
        assertThat(output).contains("Nodes: 2");
        assertThat(output).contains("Agents: 1");
    }

    @Test
    void shouldHandleParserException() throws Exception {
        // Given
        String workflowName = "invalid-syntax";
        injectField(command, "workflowName", workflowName);

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenThrow(new RuntimeException("Syntax error at line 10"));

        // When
        command.run();

        // Then
        String errOutput = errContent.toString();
        assertThat(errOutput).contains("Validation failed");
        assertThat(errOutput).contains("Syntax error at line 10");
    }

    @Test
    void shouldWarnAboutUnreachableNodes() throws Exception {
        // Given
        String workflowName = "unreachable-workflow";
        injectField(command, "workflowName", workflowName);

        Workflow workflow = createWorkflowWithUnreachableNode();

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("Workflow is valid");
        assertThat(output).contains("Unreachable nodes");
        assertThat(output).contains("orphan-node");
    }

    @Test
    void shouldNotWarnWhenAllNodesReachable() throws Exception {
        // Given
        String workflowName = "all-reachable";
        injectField(command, "workflowName", workflowName);

        Workflow workflow = createTestWorkflow("all-reachable");

        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("Workflow is valid");
        assertThat(output).doesNotContain("Warning");
        assertThat(output).doesNotContain("Unreachable");
    }
}
