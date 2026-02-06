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
class WorkflowBuildCommandTest extends BaseWorkflowCommandTest {

    @TempDir Path tempDir;

    @Mock private KotlinScriptParser kotlinParser;

    private WorkflowBuildCommand command;

    @BeforeEach
    void setUp() throws Exception {
        command = new WorkflowBuildCommand();
        injectField(command, "kotlinParser", kotlinParser);
        injectField(command, "workingDirPath", tempDir);
    }

    @Test
    void shouldCompileWorkflowToJson() throws Exception {
        // Given
        String workflowName = "my-workflow";
        injectField(command, "workflowName", workflowName);

        Workflow workflow = createTestWorkflow("my-workflow");
        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("Compiled: my-workflow");
        assertThat(output).contains("Output:");

        Path jsonFile = tempDir.resolve("build").resolve("my-workflow.json");
        assertThat(jsonFile).exists();
        assertThat(Files.readString(jsonFile)).contains("my-workflow");
    }

    @Test
    void shouldCreateBuildDirectory() throws Exception {
        // Given
        String workflowName = "new-workflow";
        injectField(command, "workflowName", workflowName);

        Workflow workflow = createTestWorkflow("new-workflow");
        when(kotlinParser.parse(any(WorkingDirectory.class), eq(workflowName)))
                .thenReturn(workflow);

        assertThat(tempDir.resolve("build")).doesNotExist();

        // When
        command.run();

        // Then
        assertThat(tempDir.resolve("build")).isDirectory();
        assertThat(tempDir.resolve("build").resolve("new-workflow.json")).exists();
    }

    @Test
    void shouldHandleUnsupportedWorkflow() throws Exception {
        // Given — no workflowName injected and no default configured
        injectField(command, "workflowName", null);
        injectField(command, "defaultWorkflowName", java.util.Optional.empty());

        // When
        command.run();

        // Then
        String errOutput = errContent.toString();
        assertThat(errOutput).contains("Build failed");
    }
}
