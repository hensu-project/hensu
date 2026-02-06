package io.hensu.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowPushCommandTest extends BaseWorkflowCommandTest {

    @TempDir Path tempDir;

    @Mock private HttpResponse<String> httpResponse;

    private WorkflowPushCommand command;

    @BeforeEach
    void setUp() throws Exception {
        command = spy(new WorkflowPushCommand());
        injectField(command, "workflowId", "my-workflow");
        injectField(command, "workingDirPath", tempDir);
        injectField(command, "serverUrl", "http://localhost:8080");
        injectField(command, "tenantId", "test-tenant");
    }

    @Test
    void shouldPushCreated() throws Exception {
        // Given
        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(buildDir);
        Files.writeString(buildDir.resolve("my-workflow.json"), "{\"id\":\"my-workflow\"}");

        doReturn(httpResponse).when(command).httpPost(anyString(), anyString());
        org.mockito.Mockito.when(httpResponse.statusCode()).thenReturn(201);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("Pushing my-workflow");
        assertThat(output).contains("Created: my-workflow");
    }

    @Test
    void shouldPushUpdated() throws Exception {
        // Given
        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(buildDir);
        Files.writeString(buildDir.resolve("my-workflow.json"), "{\"id\":\"my-workflow\"}");

        doReturn(httpResponse).when(command).httpPost(anyString(), anyString());
        org.mockito.Mockito.when(httpResponse.statusCode()).thenReturn(200);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("Updated: my-workflow");
    }

    @Test
    void shouldFailWhenJsonNotFound() {
        // Given — no build/my-workflow.json file

        // When
        command.run();

        // Then
        String errOutput = errContent.toString();
        assertThat(errOutput).contains("Compiled workflow not found");
        assertThat(errOutput).contains("Run 'hensu build' first");
    }

    @Test
    void shouldHandleHttpError() throws Exception {
        // Given
        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(buildDir);
        Files.writeString(buildDir.resolve("my-workflow.json"), "{\"id\":\"my-workflow\"}");

        doReturn(httpResponse).when(command).httpPost(anyString(), anyString());
        org.mockito.Mockito.when(httpResponse.statusCode()).thenReturn(500);
        org.mockito.Mockito.when(httpResponse.body()).thenReturn("Internal error");

        // When
        command.run();

        // Then
        String errOutput = errContent.toString();
        assertThat(errOutput).contains("Server error");
    }
}
