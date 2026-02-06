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
class WorkflowPullCommandTest extends BaseWorkflowCommandTest {

    @Mock private HttpResponse<String> httpResponse;

    private WorkflowPullCommand command;

    @BeforeEach
    void setUp() throws Exception {
        command = spy(new WorkflowPullCommand());
        injectField(command, "workflowId", "my-workflow");
        injectField(command, "serverUrl", "http://localhost:8080");
        injectField(command, "tenantId", "test-tenant");
    }

    @Test
    void shouldPullAndPrintWorkflow() {
        // Given
        String jsonBody = "{\"id\":\"my-workflow\",\"version\":\"1.0.0\"}";
        doReturn(httpResponse).when(command).httpGet(anyString());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(jsonBody);

        // When
        command.run();

        // Then
        String output = outContent.toString();
        assertThat(output).contains(jsonBody);
    }

    @Test
    void shouldHandleNotFound() {
        // Given
        doReturn(httpResponse).when(command).httpGet(anyString());
        when(httpResponse.statusCode()).thenReturn(404);

        // When
        command.run();

        // Then
        String errOutput = errContent.toString();
        assertThat(errOutput).contains("Not found");
    }
}
