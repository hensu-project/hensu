package io.hensu.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hensu.core.resume.ResumeInput;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewOutcome;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.mcp.McpConnections;
import io.hensu.server.mcp.McpSessionManager;
import io.hensu.server.mcp.McpToolDiscovery;
import io.hensu.server.mcp.McpToolProvider;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import io.hensu.server.workflow.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Integration tests for MCP tool calls over the split-pipe SSE transport.
///
/// This is the only end-to-end exercise of that transport – every other MCP
/// test in the server mocks the connection – so it drives the whole seam the
/// runtime uses: stub agent → `ToolLoopRunner` → `ToolRouter` →
/// {@link McpToolProvider} → {@link McpToolDiscovery} → `SseMcpConnection` →
/// {@link McpSessionManager} → simulated tenant server, and back.
///
/// ### Architecture
/// The tests simulate a tenant's MCP server by subscribing directly to the
/// {@link McpSessionManager} SSE stream and posting JSON-RPC responses back via
/// {@link McpSessionManager#handleResponse(String, String)}. The simulated server
/// answers both `tools/list` – which is how the tool reaches the agent's
/// catalog at all – and `tools/call`.
///
/// ### Contracts
/// - **Precondition**: Stub mode enabled (`hensu.stub.enabled=true`)
/// - **Invariant**: All executions use {@link #TEST_TENANT}, whose MCP session
///   handle is `sse://test-tenant` – the endpoint is derived from the tenant id
///   and from nothing else
///
/// @see McpSessionManager for the split-pipe SSE transport
/// @see McpToolProvider for the provider under test
/// @see IntegrationTestBase for shared test infrastructure
@QuarkusTest
class McpToolIntegrationTest extends IntegrationTestBase {

    private static final String TOOL_NAME = "read_file";

    @Inject McpSessionManager mcpSessionManager;
    @Inject McpToolDiscovery mcpToolDiscovery;
    @Inject McpToolProvider mcpToolProvider;
    @Inject TestReviewHandler testReviewHandler;

    /// Drops discovered catalogs so each test re-lists against its own
    /// simulated server rather than a catalog cached from a previous one.
    @BeforeEach
    void clearDiscoveredTools() {
        mcpToolDiscovery.invalidateAllCaches();
        testReviewHandler.reset();
    }

    /// Verifies that a tenant with no MCP session gets a failure naming the reason.
    ///
    /// The provider answers rather than throws: a provider that throws is read
    /// by the router as absent, which would hide the missing configuration
    /// behind a missing tool.
    @Test
    void shouldReportMissingEndpointRatherThanThrow() throws Exception {
        ToolCallResult result =
                TenantContext.runAs(
                        TenantInfo.simple(TEST_TENANT),
                        () -> mcpToolProvider.call(TOOL_NAME, Map.of(), Map.of()));

        assertThat(result.status()).isEqualTo(ToolCallStatus.FAILURE);
        assertThat(result.error()).contains("no MCP endpoint configured");
    }

    /// Verifies a full MCP tool call round-trip through the SSE split-pipe transport.
    ///
    /// The test creates an SSE session under the tenant id, subscribes to
    /// requests in the background, and answers `tools/list` and `tools/call`
    /// with JSON-RPC results. The agent tool loop blocks on the call until the
    /// simulated server responds, then the node transitions to the end node.
    ///
    /// ### Concurrency
    /// The workflow execution blocks its virtual thread while waiting for the
    /// MCP response. The SSE subscriber runs on the Mutiny emitter thread and
    /// breaks that wait by posting the response back through
    /// {@link McpSessionManager#handleResponse(String, String)}.
    @Test
    void shouldCallMcpToolFromAgentToolLoop() throws Exception {
        Workflow workflow = loadWorkflow("mcp-tool-loop.json");
        CountDownLatch toolCallHandled = new CountDownLatch(1);
        Cancellable relay = connectTenantServer(List.of(TOOL_NAME), toolCallHandled);

        registerStub(
                "mcp-agent",
                "[TOOL_CALL] " + TOOL_NAME + " path=/test/data.txt\n---TURN---\nRead the file.");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of());

        assertThat(toolCallHandled.await(10, TimeUnit.SECONDS))
                .as("Simulated MCP server should have received and handled the tool call")
                .isTrue();

        List<HensuSnapshot> snapshots =
                workflowStateRepository.findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.currentNodeId()).isEqualTo("done");
        relay.cancel();
    }

    /// Verifies that a relay which reconnects with a changed tool list is
    /// discovered afresh: the session ending and the new one registering both
    /// drop the cached catalog, so the tool added while the relay was away is
    /// callable and the one it removed is no longer offered.
    @Test
    void shouldRediscoverCatalogWhenRelayReconnects() throws Exception {
        TenantInfo tenant =
                TenantInfo.withMcp(TEST_TENANT, McpConnections.endpointFor(TEST_TENANT));

        Cancellable firstRelay = connectTenantServer(List.of(TOOL_NAME, "old_tool"), null);
        assertThat(toolNames(tenant)).containsExactlyInAnyOrder(TOOL_NAME, "old_tool");

        firstRelay.cancel();
        Cancellable secondRelay = connectTenantServer(List.of(TOOL_NAME, "new_tool"), null);

        assertThat(toolNames(tenant)).containsExactlyInAnyOrder(TOOL_NAME, "new_tool");
        ToolCallResult result =
                TenantContext.runAs(
                        tenant, () -> mcpToolProvider.call("new_tool", Map.of(), Map.of()));
        assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
        secondRelay.cancel();
    }

    /// Verifies that an execution resumed after a review pause still reaches the
    /// tenant's MCP tools.
    ///
    /// Resume used to bind the tenant without an MCP endpoint, so the resumed run
    /// saw an empty catalog and its first tool call never reached the relay. The
    /// tool node here runs only after the resume, so the call reaching the
    /// simulated server is the proof that the resumed run has the catalog the
    /// paused run had.
    @Test
    void shouldOfferMcpToolsToResumedExecution() throws Exception {
        Workflow workflow = loadWorkflow("mcp-review-resume.json");
        CountDownLatch toolCallHandled = new CountDownLatch(1);
        Cancellable relay = connectTenantServer(List.of(TOOL_NAME), toolCallHandled);

        registerStub("draft", "Read /test/data.txt please");
        registerStub(
                "mcp-agent",
                "[TOOL_CALL] " + TOOL_NAME + " path=/test/data.txt\n---TURN---\nRead the file.");
        testReviewHandler.enqueueOutcome(ReviewOutcome.pending("corr-mcp"));

        ExecutionStartResult result = pushAndExecute(workflow, Map.of());
        HensuSnapshot paused =
                workflowStateRepository
                        .findByExecutionId(TEST_TENANT, result.executionId())
                        .orElseThrow();
        assertThat(paused.checkpointReason()).isEqualTo("paused");
        assertThat(toolCallHandled.getCount()).as("no tool call before the pause").isOne();

        workflowService.resumeExecution(
                TEST_TENANT,
                result.executionId(),
                new ResumeInput.ApplyReview("corr-mcp", new ReviewDecision.Approve()));

        assertThat(toolCallHandled.await(10, TimeUnit.SECONDS))
                .as("Resumed execution should have called the tenant's MCP tool")
                .isTrue();
        HensuSnapshot completed =
                workflowStateRepository
                        .findByExecutionId(TEST_TENANT, result.executionId())
                        .orElseThrow();
        assertThat(completed.checkpointReason()).isEqualTo("completed");
        assertThat(completed.currentNodeId()).isEqualTo("done");
        relay.cancel();
    }

    private List<String> toolNames(TenantInfo tenant) throws Exception {
        return TenantContext.runAs(tenant, () -> mcpToolProvider.tools()).stream()
                .map(ToolDefinition::name)
                .toList();
    }

    /// Connects a simulated tenant MCP server under {@link #TEST_TENANT} that
    /// lists `toolNames` and answers every `tools/call` with a text result.
    ///
    /// @param toolNames the tools the relay publishes, not null
    /// @param toolCallHandled counted down after each answered `tools/call`, may be null
    /// @return the subscription; cancelling it disconnects the relay, never null
    private Cancellable connectTenantServer(
            List<String> toolNames, CountDownLatch toolCallHandled) {
        ObjectMapper mapper = new ObjectMapper();

        // The endpoint is derived from the tenant id, so the simulated server
        // must register under exactly that id for the pipe to line up.
        Multi<String> events = mcpSessionManager.createSession(TEST_TENANT);

        // Subscribe in the background to simulate the tenant's MCP server.
        // Notifications carry no id (the initial ping) and are skipped.
        Cancellable subscription =
                events.subscribe()
                        .with(
                                event -> {
                                    try {
                                        JsonNode message = mapper.readTree(event);
                                        JsonNode idNode = message.get("id");
                                        if (idNode == null || idNode.isNull()) {
                                            return;
                                        }

                                        String method = message.path("method").asText();
                                        ObjectNode response = mapper.createObjectNode();
                                        response.put("jsonrpc", "2.0");
                                        response.put("id", idNode.asText());
                                        ObjectNode result = response.putObject("result");

                                        if ("tools/list".equals(method)) {
                                            var tools = result.putArray("tools");
                                            for (String name : toolNames) {
                                                ObjectNode tool = tools.addObject();
                                                tool.put("name", name);
                                                tool.put(
                                                        "description", "A tool the tenant exposes");
                                                ObjectNode schema = tool.putObject("inputSchema");
                                                schema.put("type", "object");
                                                schema.putObject("properties")
                                                        .putObject("path")
                                                        .put("type", "string");
                                            }
                                        } else {
                                            result.putArray("content")
                                                    .addObject()
                                                    .put("type", "text")
                                                    .put("text", "file data from MCP");
                                        }

                                        mcpSessionManager.handleResponse(
                                                TEST_TENANT, mapper.writeValueAsString(response));

                                        if ("tools/call".equals(method)
                                                && toolCallHandled != null) {
                                            toolCallHandled.countDown();
                                        }
                                    } catch (Exception e) {
                                        throw new RuntimeException(
                                                "Simulated MCP server failed", e);
                                    }
                                },
                                _ -> {
                                    // SSE stream error -- ignore in test
                                });

        // The emitter registers lazily on subscription, so poll until connected.
        Uni.createFrom()
                .item(() -> mcpSessionManager.isConnected(TEST_TENANT))
                .repeat()
                .withDelay(Duration.ofMillis(20))
                .until(isConnected -> isConnected)
                .collect()
                .last()
                .await()
                .atMost(Duration.ofSeconds(1));

        assertThat(mcpSessionManager.isConnected(TEST_TENANT))
                .as("SSE session should be established before workflow execution")
                .isTrue();
        return subscription;
    }
}
