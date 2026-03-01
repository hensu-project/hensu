package io.hensu.integrations.springclient.client;

import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/// Blocking REST client for hensu-server workflow and execution APIs.
///
/// Wraps `RestClient` calls to:
/// - `POST /api/v1/executions` — start a workflow execution
/// - `POST /api/v1/executions/{id}/resume` — approve or reject a paused execution
/// - `GET /api/v1/executions/{id}/result` — retrieve the final execution output
///
/// All methods are synchronous and suitable for use in servlet threads or
/// `Schedulers.boundedElastic()` when called from a reactive context.
@Component
public class HensuClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public HensuClient(RestClient hensuRestClient) {
        this.restClient = hensuRestClient;
    }

    /// Starts a new workflow execution.
    ///
    /// Calls `POST /api/v1/executions` and returns immediately — the workflow
    /// runs asynchronously. Subscribe to the SSE event stream via
    /// {@link HensuEventStream} to monitor progress.
    ///
    /// @param workflowId ID of the workflow to execute, must exist on the server
    /// @param context    initial context variables passed into the workflow
    /// @return start result containing the assigned `executionId`
    public StartResult startExecution(String workflowId, Map<String, Object> context) {
        return restClient.post()
                .uri("/api/v1/executions")
                .body(new StartRequest(workflowId, context))
                .retrieve()
                .body(StartResult.class);
    }

    /// Resumes a paused execution after human review.
    ///
    /// Calls `POST /api/v1/executions/{id}/resume`. The execution must be in
    /// the `PAUSED` state — check for an `execution.paused` SSE event first.
    ///
    /// @param executionId   the execution to resume
    /// @param approved      true to approve, false to reject the pending plan
    /// @param modifications optional context overrides applied before resuming
    public void resume(String executionId, boolean approved, Map<String, Object> modifications) {
        restClient.post()
                .uri("/api/v1/executions/{id}/resume", executionId)
                .body(new ResumeRequest(approved, modifications))
                .retrieve()
                .toBodilessEntity();
    }

    /// Returns the final output of a completed or paused execution.
    ///
    /// Use as a fallback when not connected to the SSE stream, or to fetch
    /// the result after receiving `execution.completed`.
    ///
    /// @param executionId the execution to query
    /// @return raw response map with keys `executionId`, `workflowId`, `status`, `output`
    public Map<String, Object> getResult(String executionId) {
        return restClient.get()
                .uri("/api/v1/executions/{id}/result", executionId)
                .retrieve()
                .body(MAP_TYPE);
    }

    // -------------------------------------------------------------------------
    // Request / response records
    // -------------------------------------------------------------------------

    /// Request body for `POST /api/v1/executions`.
    public record StartRequest(String workflowId, Map<String, Object> context) {}

    /// Response from `POST /api/v1/executions`.
    public record StartResult(String executionId, String workflowId) {}

    /// Request body for `POST /api/v1/executions/{id}/resume`.
    public record ResumeRequest(boolean approved, Map<String, Object> modifications) {}
}
