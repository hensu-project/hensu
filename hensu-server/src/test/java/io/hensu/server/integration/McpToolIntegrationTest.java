package io.hensu.server.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.resume.ResumeInput;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewOutcome;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.mcp.McpToolProvider;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import io.hensu.server.workflow.ExecutionStartResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// End-to-end tests of the relay protocol over HTTP: a test relay publishes its catalog,
/// long-polls for work, acknowledges each offer before executing it, and completes it –
/// exactly what a tenant's relay does against any node of a cluster.
@QuarkusTest
@TestProfile(InMemoryTestProfile.class)
@TestSecurity(user = "test-user")
class McpToolIntegrationTest extends IntegrationTestBase {

    private static final String TOOL_NAME = "read_file";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject McpToolProvider mcpToolProvider;
    @Inject TestReviewHandler testReviewHandler;

    private TestRelay relay;

    @AfterEach
    void stopRelay() {
        if (relay != null) {
            relay.stop();
        }
    }

    @Test
    void agentToolCallReachesTheRelayAndItsAnswerReachesTheAgent() {
        Workflow workflow = loadWorkflow("mcp-tool-loop.json");
        relay = TestRelay.start(List.of(TOOL_NAME));
        registerStub(
                "mcp-agent",
                "[TOOL_CALL] " + TOOL_NAME + " path=/test/data.txt\n---TURN---\nRead the file.");

        ExecutionStartResult result = pushAndExecute(workflow, Map.of());

        HensuSnapshot snapshot =
                stateReader.findByExecutionId(TEST_TENANT, result.executionId()).orElseThrow();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(relay.executed)
                .singleElement()
                .satisfies(
                        call -> {
                            assertThat(call.path("tool").asText()).isEqualTo(TOOL_NAME);
                            assertThat(call.path("arguments").path("path").asText())
                                    .isEqualTo("/test/data.txt");
                            // Unique within the tenant: two executions at the same step
                            // must not hand a relay the same key to de-duplicate on.
                            assertThat(call.path("key").asText())
                                    .isEqualTo(result.executionId() + "/0#1");
                        });
    }

    @Test
    void aRepublishedCatalogReplacesTheToolsAndAStaleVersionIsRefused() throws Exception {
        publish("v1", List.of(TOOL_NAME, "old_tool"));
        assertThat(toolNames()).containsExactlyInAnyOrder(TOOL_NAME, "old_tool");

        publish("v2", List.of(TOOL_NAME, "new_tool"));

        assertThat(toolNames()).containsExactlyInAnyOrder(TOOL_NAME, "new_tool");
        given().contentType(ContentType.JSON)
                .body("{\"catalogVersion\":\"v1\",\"waitMs\":0}")
                .post("/mcp/v1/poll")
                .then()
                .statusCode(409);
    }

    @Test
    void anOfferThatIsNotCurrentIsRefusedSoTheRelayDoesNotExecuteIt() {
        String token = "00000000-0000-0000-0000-000000000000";

        int ack = given().post("/mcp/v1/tasks/" + token + "/ack").statusCode();
        int complete =
                given().contentType(ContentType.JSON)
                        .body("{\"result\":{\"content\":[]}}")
                        .post("/mcp/v1/tasks/" + token + "/complete")
                        .statusCode();

        assertThat(ack).as("acknowledging a token never offered").isEqualTo(409);
        assertThat(complete).as("completing a call never started").isEqualTo(404);
    }

    @Test
    void aResumedExecutionReachesTheTenantsTools() {
        Workflow workflow = loadWorkflow("mcp-review-resume.json");
        relay = TestRelay.start(List.of(TOOL_NAME));
        registerStub("draft", "Read /test/data.txt please");
        registerStub(
                "mcp-agent",
                "[TOOL_CALL] " + TOOL_NAME + " path=/test/data.txt\n---TURN---\nRead the file.");
        testReviewHandler.enqueueOutcome(ReviewOutcome.pending("corr-mcp"));

        ExecutionStartResult result = pushAndExecute(workflow, Map.of());
        assertThat(
                        stateReader
                                .findByExecutionId(TEST_TENANT, result.executionId())
                                .orElseThrow()
                                .checkpointReason())
                .isEqualTo("paused");
        assertThat(relay.executed).as("no tool call before the pause").isEmpty();

        workflowService.resumeExecution(
                TEST_TENANT,
                result.executionId(),
                new ResumeInput.ApplyReview("corr-mcp", new ReviewDecision.Approve()));

        assertThat(relay.executed).hasSize(1);
        HensuSnapshot completed =
                stateReader.findByExecutionId(TEST_TENANT, result.executionId()).orElseThrow();
        assertThat(completed.checkpointReason()).isEqualTo("completed");
        assertThat(completed.currentNodeId()).isEqualTo("done");
    }

    private List<String> toolNames() throws Exception {
        return TenantContext.runAs(TenantInfo.simple(TEST_TENANT), mcpToolProvider::tools).stream()
                .map(ToolDefinition::name)
                .toList();
    }

    private static void publish(String version, List<String> tools) {
        given().contentType(ContentType.JSON)
                .body(catalog(version, tools))
                .put("/mcp/v1/catalog")
                .then()
                .statusCode(204);
    }

    private static String catalog(String version, List<String> tools) {
        StringBuilder json = new StringBuilder("{\"version\":\"" + version + "\",\"tools\":[");
        for (int i = 0; i < tools.size(); i++) {
            json.append(i > 0 ? "," : "")
                    .append("{\"name\":\"")
                    .append(tools.get(i))
                    .append("\",\"description\":\"test\",\"inputSchema\":{\"type\":\"object\",")
                    .append("\"properties\":{\"path\":{\"type\":\"string\"}}}}");
        }
        return json.append("]}").toString();
    }

    /// A relay following the protocol: ack before execute, complete after.
    static final class TestRelay {
        final Queue<JsonNode> executed = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private Thread thread;

        static TestRelay start(List<String> tools) {
            publish("v1", tools);
            TestRelay relay = new TestRelay();
            relay.thread = Thread.ofVirtual().start(relay::loop);
            return relay;
        }

        private void loop() {
            while (running.get()) {
                try {
                    String body =
                            given().contentType(ContentType.JSON)
                                    .body("{\"catalogVersion\":\"" + "v1" + "\",\"waitMs\":200}")
                                    .post("/mcp/v1/poll")
                                    .then()
                                    .statusCode(200)
                                    .extract()
                                    .asString();
                    for (JsonNode task : JSON.readTree(body).path("tasks")) {
                        String token = task.path("token").asText();
                        int ack = given().post("/mcp/v1/tasks/" + token + "/ack").statusCode();
                        if (ack != 204) {
                            continue;
                        }
                        executed.add(task);
                        given().contentType(ContentType.JSON)
                                .body(
                                        "{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"file"
                                                + " contents\"}]}}")
                                .post("/mcp/v1/tasks/" + token + "/complete")
                                .then()
                                .statusCode(204);
                    }
                } catch (Exception | AssertionError e) {
                    if (!running.get()) {
                        return;
                    }
                }
            }
        }

        void stop() {
            running.set(false);
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
