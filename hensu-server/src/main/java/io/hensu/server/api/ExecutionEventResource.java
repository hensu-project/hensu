package io.hensu.server.api;

import io.hensu.server.security.RequestTenantResolver;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

/// SSE endpoint for streaming execution events.
///
/// Clients can subscribe to real-time execution events via Server-Sent Events.
/// Events are streamed as JSON objects with the following format:
///
/// ```
/// event: step.started
/// data: {"executionId":"exec-123","planId":"plan-456","stepIndex":0,...}
///
/// event: step.completed
/// data: {"executionId":"exec-123","planId":"plan-456","stepIndex":0,"success":true,...}
/// ```
///
/// ### Usage
/// ```javascript
/// const eventSource = new EventSource('/api/v1/executions/exec-123/events');
///
/// eventSource.addEventListener('step.started', (e) => {
///     const data = JSON.parse(e.data);
///     console.log(`Step ${data.stepIndex} started: ${data.toolName}`);
/// });
///
/// eventSource.addEventListener('execution.completed', (e) => {
///     const data = JSON.parse(e.data);
///     console.log(`Execution ${data.success ? 'succeeded' : 'failed'}`);
///     eventSource.close();
/// });
/// ```
///
/// ### Event Types
/// - `execution.started` - Execution began
/// - `plan.created` - Plan was generated
/// - `step.started` - Step execution began
/// - `step.completed` - Step finished (success or failure)
/// - `plan.revised` - Plan was modified after failure
/// - `plan.completed` - Plan execution finished
/// - `execution.paused` - Awaiting human review
/// - `execution.completed` - Workflow finished
/// - `execution.error` - Error occurred
///
/// @see ExecutionEventBroadcaster for event publishing
/// @see ExecutionEvent for event DTOs
@Path("/api/v1/executions")
public class ExecutionEventResource {

    private static final Logger LOG = Logger.getLogger(ExecutionEventResource.class);

    private final ExecutionEventBroadcaster broadcaster;
    private final RequestTenantResolver tenantResolver;

    @Inject
    public ExecutionEventResource(
            ExecutionEventBroadcaster broadcaster, RequestTenantResolver tenantResolver) {
        this.broadcaster = broadcaster;
        this.tenantResolver = tenantResolver;
    }

    /// Subscribes to execution events via SSE.
    ///
    /// ### Request
    /// ```
    /// GET /api/v1/executions/{executionId}/events
    /// Authorization: Bearer <jwt>
    /// Accept: text/event-stream
    /// ```
    ///
    /// ### Response (SSE stream)
    /// ```
    /// event: execution.started
    /// data: {"executionId":"exec-123","workflowId":"wf-1","tenantId":"tenant-123",...}
    ///
    /// event: step.started
    /// data: {"executionId":"exec-123","planId":"plan-456","stepIndex":0,...}
    /// ```
    ///
    /// @param executionId the execution to subscribe to
    /// @return SSE event stream
    @GET
    @Path("/{executionId}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<ExecutionEvent> streamEvents(@PathParam("executionId") String executionId) {

        String tenantId = tenantResolver.tenantId();

        LOG.infov("SSE subscription: executionId={0}, tenant={1}", executionId, tenantId);

        return broadcaster
                .subscribe(executionId)
                .onSubscription()
                .invoke(() -> LOG.debugv("Client subscribed to execution: {0}", executionId))
                .onTermination()
                .invoke(
                        (t, c) -> {
                            if (t != null) {
                                LOG.warnv(t, "SSE stream error for execution: {0}", executionId);
                            } else if (c) {
                                LOG.debugv("SSE stream cancelled for execution: {0}", executionId);
                            } else {
                                LOG.debugv("SSE stream completed for execution: {0}", executionId);
                            }
                        });
    }

    /// Subscribes to all events for a tenant (debug/admin endpoint).
    ///
    /// Streams all execution events for the tenant. Useful for monitoring dashboards.
    ///
    /// @return SSE event stream for all tenant executions
    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<ExecutionEvent> streamAllEvents() {

        String tenantId = tenantResolver.tenantId();

        LOG.infov("SSE subscription for all executions: tenant={0}", tenantId);

        // For tenant-wide streaming, we create a special "all" subscription
        // that aggregates events from all executions for this tenant
        String subscriptionId = "tenant:" + tenantId;

        return broadcaster
                .subscribe(subscriptionId)
                .onSubscription()
                .invoke(
                        () ->
                                LOG.debugv(
                                        "Client subscribed to all events for tenant: {0}",
                                        tenantId));
    }
}
