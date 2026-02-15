package io.hensu.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowListCommandTest extends BaseWorkflowCommandTest {

    @Mock private HttpResponse<String> httpResponse;

    private WorkflowListCommand command;

    @BeforeEach
    void setUp() throws Exception {
        command = spy(new WorkflowListCommand());
        injectField(command, "serverUrl", "http://localhost:8080");
        injectField(command, "token", "test-tenant");
    }

    @Test
    void shouldListWorkflowsAsTable() {
        // Given
        String jsonBody =
                """
                [
                    {"id": "workflow-a", "version": "1.0.0"},
                    {"id": "workflow-b", "version": "2.1.0"}
                ]
                """;
        doReturn(httpResponse).when(command).httpGet(anyString());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(jsonBody);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("ID");
        assertThat(output).contains("VERSION");
        assertThat(output).contains("workflow-a");
        assertThat(output).contains("1.0.0");
        assertThat(output).contains("workflow-b");
        assertThat(output).contains("2.1.0");
    }

    @Test
    void shouldHandleEmptyList() {
        // Given
        doReturn(httpResponse).when(command).httpGet(anyString());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("[]");

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains("No workflows found.");
    }

    @Test
    void shouldHandleHttpError() {
        // Given
        doReturn(httpResponse).when(command).httpGet(anyString());
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("Internal error");

        // When
        command.run();

        // Then
        String errOutput = errContent.toString();
        assertThat(errOutput).contains("Server error");
    }

    @Test
    void shouldHandleInvalidJson() {
        // Given
        doReturn(httpResponse).when(command).httpGet(anyString());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("not valid json");

        // When
        command.run();

        // Then
        String errOutput = errContent.toString();
        assertThat(errOutput).contains("Failed to parse response");
    }
}
