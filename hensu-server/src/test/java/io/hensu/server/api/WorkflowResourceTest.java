package io.hensu.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.server.service.WorkflowService;
import io.hensu.server.service.WorkflowService.ExecutionNotFoundException;
import io.hensu.server.service.WorkflowService.ExecutionStartResult;
import io.hensu.server.service.WorkflowService.ExecutionStatus;
import io.hensu.server.service.WorkflowService.ExecutionSummary;
import io.hensu.server.service.WorkflowService.ResumeDecision;
import io.hensu.server.service.WorkflowService.WorkflowNotFoundException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WorkflowResourceTest {

    private WorkflowService workflowService;
    private WorkflowResource resource;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        resource = new WorkflowResource(workflowService);
    }

    @Nested
    class Execute {

        @Test
        void shouldStartExecutionAndReturn202() {
            when(workflowService.startExecution(eq("tenant-1"), eq("wf-1"), any()))
                    .thenReturn(new ExecutionStartResult("exec-123", "wf-1"));

            Response response = resource.execute("wf-1", "tenant-1", Map.of("key", "value"));

            assertThat(response.getStatus()).isEqualTo(202);
            Map<String, Object> entity = (Map<String, Object>) response.getEntity();
            assertThat(entity.get("executionId")).isEqualTo("exec-123");
            assertThat(entity.get("workflowId")).isEqualTo("wf-1");
        }

        @Test
        void shouldReturn404WhenWorkflowNotFound() {
            when(workflowService.startExecution(any(), any(), any()))
                    .thenThrow(new WorkflowNotFoundException("Not found"));

            try {
                resource.execute("wf-1", "tenant-1", Map.of());
            } catch (NotFoundException e) {
                assertThat(e.getMessage()).contains("Not found");
            }
        }

        @Test
        void shouldReturn400WhenTenantIdMissing() {
            try {
                resource.execute("wf-1", null, Map.of());
            } catch (BadRequestException e) {
                assertThat(e.getMessage()).contains("X-Tenant-ID");
            }
        }

        @Test
        void shouldReturn400WhenTenantIdBlank() {
            try {
                resource.execute("wf-1", "   ", Map.of());
            } catch (BadRequestException e) {
                assertThat(e.getMessage()).contains("X-Tenant-ID");
            }
        }
    }

    @Nested
    class Resume {

        @Test
        void shouldResumeExecutionAndReturn200() {
            Response response =
                    resource.resume(
                            "exec-1",
                            "tenant-1",
                            new WorkflowResource.ResumeRequest(true, Map.of()));

            assertThat(response.getStatus()).isEqualTo(200);
            verify(workflowService)
                    .resumeExecution(eq("tenant-1"), eq("exec-1"), any(ResumeDecision.class));
        }

        @Test
        void shouldReturn404WhenExecutionNotFound() {
            doThrow(new ExecutionNotFoundException("Not found"))
                    .when(workflowService)
                    .resumeExecution(any(), any(), any());

            try {
                resource.resume("exec-1", "tenant-1", null);
            } catch (NotFoundException e) {
                assertThat(e.getMessage()).contains("Not found");
            }
        }
    }

    @Nested
    class GetExecution {

        @Test
        void shouldReturnExecutionStatus() {
            when(workflowService.getExecutionStatus("tenant-1", "exec-1"))
                    .thenReturn(new ExecutionStatus("exec-1", "wf-1", "PAUSED", "node-1", false));

            Response response = resource.getExecution("exec-1", "tenant-1");

            assertThat(response.getStatus()).isEqualTo(200);
            Map<String, Object> entity = (Map<String, Object>) response.getEntity();
            assertThat(entity.get("status")).isEqualTo("PAUSED");
        }

        @Test
        void shouldReturn404WhenExecutionNotFound() {
            when(workflowService.getExecutionStatus(any(), any()))
                    .thenThrow(new ExecutionNotFoundException("Not found"));

            try {
                resource.getExecution("exec-1", "tenant-1");
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

            Response response = resource.listPausedExecutions("tenant-1");

            assertThat(response.getStatus()).isEqualTo(200);
            List<Map<String, Object>> entity = (List<Map<String, Object>>) response.getEntity();
            assertThat(entity).hasSize(2);
        }

        @Test
        void shouldReturnEmptyListWhenNoPausedExecutions() {
            when(workflowService.listPausedExecutions("tenant-1")).thenReturn(List.of());

            Response response = resource.listPausedExecutions("tenant-1");

            assertThat(response.getStatus()).isEqualTo(200);
            List<Map<String, Object>> entity = (List<Map<String, Object>>) response.getEntity();
            assertThat(entity).isEmpty();
        }
    }
}
