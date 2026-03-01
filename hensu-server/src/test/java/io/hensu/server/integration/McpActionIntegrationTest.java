package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.mcp.McpSessionManager;
import io.hensu.server.workflow.WorkflowService.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/// Integration tests for MCP (Model Context Protocol) action node execution.
///
/// Validates the full MCP round-trip: workflow engine dispatches a tool call
/// via {@link io.hensu.server.mcp.McpSidecar}, the request travels through
/// {@link McpSessionManager} over SSE, a simulated MCP server responds, and
/// the workflow completes with the tool result.
///
/// Also covers the rejection path when no MCP endpoint is configured for the
/// tenant, verifying that the action handler fails gracefully.
///
/// ### Architecture
/// The tests simulate an MCP server by subscribing directly to the
/// {@link McpSessionManager} SSE stream and posting JSON-RPC responses
/// back via {@link McpSessionManager#handleResponse(String)}.
///
/// ### Contracts
/// - **Precondition**: Stub mode enabled (`hensu.stub.enabled=true`)
/// - **Invariant**: All executions use {@link #TEST_TENANT}
///
/// @see McpSessionManager for the split-pipe SSE transport
/// @see io.hensu.server.mcp.McpSidecar for the action handler under test
/// @see IntegrationTestBase for shared test infrastructure
@QuarkusTest
class McpActionIntegrationTest extends IntegrationTestBase {

    @Inject McpSessionManager mcpSessionManager;

    /// Verifies that an MCP action node fails when the tenant has no MCP endpoint.
    ///
    /// Without an MCP endpoint, {@link io.hensu.server.mcp.McpSidecar} returns
    /// {@link io.hensu.core.execution.action.ActionExecutor.ActionResult#failure},
    /// which causes the action node to produce a FAILURE result. Since the
    /// `mcp-action.json` workflow only defines a "success" transition, no valid
    /// transition is found and execution is persisted as `failed`.
    @Test
    void shouldRejectMcpWithoutTenantEndpoint() {
        Workflow workflow = loadWorkflow("mcp-action.json");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of());

        HensuSnapshot snapshot =
                workflowStateRepository
                        .findByExecutionId(TEST_TENANT, result.executionId())
                        .orElseThrow();
        assertThat(snapshot.checkpointReason()).isEqualTo("failed");
    }

    /// Verifies a full MCP tool call round-trip through the SSE split-pipe transport.
    ///
    /// The test creates an SSE session, subscribes to tool call requests in the
    /// background, and responds with a JSON-RPC result. The workflow engine blocks
    /// on the tool call until the simulated MCP server responds, then transitions
    /// to the end node.
    ///
    /// ### Concurrency
    /// The workflow execution is synchronous and blocks the calling thread while
    /// waiting for the MCP response. The SSE subscriber runs on a separate virtual
    /// thread to break the deadlock: it receives the JSON-RPC request and posts the
    /// response back through {@link McpSessionManager#handleResponse(String)}.
    @Test
    void shouldCallMcpToolViaActionNode() throws Exception {
        Workflow workflow = loadWorkflow("mcp-action.json");
        String clientId = "test-mcp-client";
        ObjectMapper mapper = new ObjectMapper();

        // Create SSE session so the MCP connection pool can find this client
        Multi<String> events = mcpSessionManager.createSession(clientId);

        // Latch to confirm the simulated server received and handled the tool call
        CountDownLatch toolCallHandled = new CountDownLatch(1);

        // Subscribe in the background to simulate an MCP server.
        // The first event is always a "ping" notification (no ID) -- skip it.
        // The second event is the actual "tools/call" request -- respond to it.
        events.subscribe()
                .with(
                        event -> {
                            try {
                                JsonNode message = mapper.readTree(event);

                                // Skip notifications (no ID field, e.g. the initial ping)
                                JsonNode idNode = message.get("id");
                                if (idNode == null || idNode.isNull()) {
                                    return;
                                }

                                // Build a JSON-RPC success response with matching ID
                                String requestId = idNode.asText();
                                ObjectNode response = mapper.createObjectNode();
                                response.put("jsonrpc", "2.0");
                                response.put("id", requestId);
                                ObjectNode result = response.putObject("result");
                                result.putArray("content")
                                        .addObject()
                                        .put("type", "text")
                                        .put("text", "file data from MCP");

                                mcpSessionManager.handleResponse(
                                        mapper.writeValueAsString(response));
                                toolCallHandled.countDown();
                            } catch (Exception e) {
                                throw new RuntimeException("Simulated MCP server failed", e);
                            }
                        },
                        _ -> {
                            // SSE stream error -- ignore in test
                        });

        // Wait for the SSE session to be registered before executing the workflow.
        // The emitter is registered lazily on subscription, so we poll until connected.
        Uni.createFrom()
                .item(() -> mcpSessionManager.isConnected(clientId))
                .repeat()
                .withDelay(Duration.ofMillis(20))
                .until(isConnected -> isConnected)
                .collect()
                .last()
                .await()
                .atMost(Duration.ofSeconds(1));

        assertThat(mcpSessionManager.isConnected(clientId))
                .as("SSE session should be established before workflow execution")
                .isTrue();

        // Execute workflow with MCP endpoint pointing to our simulated client
        ExecutionStartResult result =
                pushAndExecuteWithMcp(workflow, Map.of(), "sse://" + clientId);

        // Verify the simulated server handled the tool call
        assertThat(toolCallHandled.await(10, TimeUnit.SECONDS))
                .as("Simulated MCP server should have received and handled the tool call")
                .isTrue();

        // Verify workflow completed successfully
        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("done");
    }
}
