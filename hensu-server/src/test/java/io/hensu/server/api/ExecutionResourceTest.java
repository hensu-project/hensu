package io.hensu.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.server.security.RequestTenantResolver;
import io.hensu.server.service.WorkflowService;
import io.hensu.server.service.WorkflowService.ExecutionNotFoundException;
import io.hensu.server.service.WorkflowService.ExecutionStartResult;
import io.hensu.server.service.WorkflowService.ExecutionStatus;
import io.hensu.server.service.WorkflowService.ExecutionSummary;
import io.hensu.server.service.WorkflowService.ResumeDecision;
import io.hensu.server.service.WorkflowService.WorkflowNotFoundException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

            var request =
                    new ExecutionResource.ExecutionStartRequest("wf-1", Map.of("key", "value"));
            Map<String, Object> entity;
            try (Response response = resource.startExecution(request)) {

                assertThat(response.getStatus()).isEqualTo(202);
                entity = (Map<String, Object>) response.getEntity();
            }
            assertThat(entity.get("executionId")).isEqualTo("exec-123");
            assertThat(entity.get("workflowId")).isEqualTo("wf-1");
        }

        @Test
        void shouldReturn404WhenWorkflowNotFound() {
            when(workflowService.startExecution(any(), any(), any()))
                    .thenThrow(new WorkflowNotFoundException("Not found"));

            var request = new ExecutionResource.ExecutionStartRequest("wf-1", Map.of());
            assertThatThrownBy(
                            () -> {
                                try (var _ = resource.startExecution(request)) {
                                    // No-op
                                }
                            })
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Not found");
        }
    }

    @Nested
    class Resume {

        @Test
        void shouldResumeExecutionAndReturn200() {
            try (Response response =
                    resource.resume(
                            "exec-1", new ExecutionResource.ResumeRequest(true, Map.of()))) {

                assertThat(response.getStatus()).isEqualTo(200);
            }
            verify(workflowService)
                    .resumeExecution(eq("tenant-1"), eq("exec-1"), any(ResumeDecision.class));
        }

        @Test
        void shouldReturn404WhenExecutionNotFound() {
            doThrow(new ExecutionNotFoundException("Not found"))
                    .when(workflowService)
                    .resumeExecution(any(), any(), any());

            assertThatThrownBy(
                            () -> {
                                try (var _ = resource.resume("exec-1", null)) {
                                    // No-op
                                }
                            })
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Not found");
        }
    }

    @Nested
    class GetExecution {

        @Test
        void shouldReturnExecutionStatus() {
            when(workflowService.getExecutionStatus("tenant-1", "exec-1"))
                    .thenReturn(new ExecutionStatus("exec-1", "wf-1", "PAUSED", "node-1", false));

            Response response = resource.getExecution("exec-1");

            assertThat(response.getStatus()).isEqualTo(200);
            Map<String, Object> entity = (Map<String, Object>) response.getEntity();
            assertThat(entity.get("status")).isEqualTo("PAUSED");
        }

        @Test
        void shouldReturn404WhenExecutionNotFound() {
            when(workflowService.getExecutionStatus(any(), any()))
                    .thenThrow(new ExecutionNotFoundException("Not found"));

            try {
                resource.getExecution("exec-1");
            } catch (NotFoundException e) {
                assertThat(e.getMessage()).contains("Not found");
            }
        }
    }

    @Nested
    class ListPausedExecutions {

        @Test
        void shouldReturnPausedExecutions() {
            when(workflowService.listPausedExecutions("tenant-1"))
                    .thenReturn(
                            List.of(
                                    new ExecutionSummary("exec-1", "wf-1", "node-1", Instant.now()),
                                    new ExecutionSummary(
                                            "exec-2", "wf-2", "node-2", Instant.now())));

            List<Map<String, Object>> entity;
            try (Response response = resource.listPausedExecutions()) {

                assertThat(response.getStatus()).isEqualTo(200);
                entity = (List<Map<String, Object>>) response.getEntity();
            }
            assertThat(entity).hasSize(2);
        }

        @Test
        void shouldReturnEmptyListWhenNoPausedExecutions() {
            when(workflowService.listPausedExecutions("tenant-1")).thenReturn(List.of());

            List<Map<String, Object>> entity;
            try (Response response = resource.listPausedExecutions()) {

                assertThat(response.getStatus()).isEqualTo(200);
                entity = (List<Map<String, Object>>) response.getEntity();
            }
            assertThat(entity).isEmpty();
        }
    }
}
