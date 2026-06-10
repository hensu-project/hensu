package io.hensu.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.resume.ResumeInput;
import io.hensu.server.security.RequestTenantResolver;
import io.hensu.server.workflow.ExecutionStartResult;
import io.hensu.server.workflow.WorkflowService;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExecutionResourceTest {

    private WorkflowService workflowService;
    private ExecutionResource resource;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        RequestTenantResolver tenantResolver = mock(RequestTenantResolver.class);
        when(tenantResolver.tenantId()).thenReturn("tenant-1");
        resource = new ExecutionResource(workflowService, tenantResolver);
    }

    @Nested
    class StartExecution {

        @Test
        void shouldStartExecutionAndReturn202() {
            when(workflowService.startExecution(eq("tenant-1"), eq("wf-1"), any()))
                    .thenReturn(new ExecutionStartResult("exec-123", "wf-1"));

            var request = new ExecutionStartRequest("wf-1", Map.of("key", "value"));
            try (Response response = resource.startExecution(request)) {

                assertThat(response.getStatus()).isEqualTo(202);
                var entity = (ExecutionStartResult) response.getEntity();
                assertThat(entity.executionId()).isEqualTo("exec-123");
                assertThat(entity.workflowId()).isEqualTo("wf-1");
            }
        }
    }

    @Nested
    class Resume {

        @Test
        void shouldResumeWithApproveDecision() {
            try (Response response =
                    resource.resume(
                            "exec-1", new ResumeRequest("corr-1", "approve", null, null, null))) {

                assertThat(response.getStatus()).isEqualTo(200);
            }

            ArgumentCaptor<ResumeInput> captor = ArgumentCaptor.forClass(ResumeInput.class);
            verify(workflowService).resumeExecution(eq("tenant-1"), eq("exec-1"), captor.capture());
            assertThat(captor.getValue()).isInstanceOf(ResumeInput.ApplyReview.class);
        }
    }
}
