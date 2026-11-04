package io.hensu.server.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.server.validation.ValidId;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

/// Integration tests for Bean Validation on REST endpoints.
///
/// Validates that user-provided `@PathParam`, `@QueryParam`, and JSON body
/// parameters are rejected with HTTP 400 when they violate constraints.
/// These tests run through the full Quarkus JAX-RS pipeline so that
/// Hibernate Validator interceptors are active.
///
/// @see ValidId
/// @see io.hensu.server.security.GlobalExceptionMapper
@QuarkusTest
@TestProfile(InMemoryTestProfile.class)
@TestSecurity(user = "test-user")
class InputValidationIntegrationTest {

    // --- Execution endpoints ---

    @Test
    void executionShouldRejectMissingWorkflowId() {
        Response response =
                given().contentType(ContentType.JSON)
                        .body("{\"context\":{}}")
                        .when()
                        .post("/api/v1/executions");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).containsIgnoringCase("workflowId");
    }

    @Test
    void executionShouldRejectBlankWorkflowId() {
        Response response =
                given().contentType(ContentType.JSON)
                        .body("{\"workflowId\":\"\",\"context\":{}}")
                        .when()
                        .post("/api/v1/executions");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).containsIgnoringCase("workflowId");
    }

    /// The key is checked before the start reaches the service: an unknown workflow would
    /// otherwise answer 404, so a 400 here proves the constraint ran first.
    @Test
    void executionShouldRejectAMalformedIdempotencyKey() {
        Response response =
                given().contentType(ContentType.JSON)
                        .header("Idempotency-Key", "has spaces")
                        .body("{\"workflowId\":\"no-such-workflow\",\"context\":{}}")
                        .when()
                        .post("/api/v1/executions");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
        assertThat(response.jsonPath().getString("error"))
                .isEqualTo("idempotencyKey: must be 1-255 characters from [A-Za-z0-9._:-]");
    }

    @Test
    void executionShouldRejectSpecialCharsInExecutionId() {
        Response response = given().when().get("/api/v1/executions/exec%3B+DROP+TABLE");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).contains("valid identifier");
    }

    @Test
    void executionShouldRejectXssInResumeExecutionId() {
        Response response =
                given().contentType(ContentType.JSON)
                        .body("{\"approved\":true}")
                        .when()
                        .post("/api/v1/executions/<script>/resume");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).contains("valid identifier");
    }

    /// A review-shaped body spelled the wrong way must be a client error, not a silent no-op.
    ///
    /// Posted through the running server this exercises the Quarkus-configured `ObjectMapper`,
    /// whose `fail-on-unknown-properties=false` is exactly what made the degradation possible:
    /// the rejection has to come from the DTO itself.
    @Test
    void resumeShouldRejectUnrecognizedBodyFields() {
        Response response =
                given().contentType(ContentType.JSON)
                        .body("{\"approved\":true,\"modifications\":{}}")
                        .when()
                        .post("/api/v1/executions/exec-1/resume");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).contains("approved");
    }

    /// A null-valued unknown field must produce the same named rejection, not an NPE inside
    /// the record constructor while Jackson is still binding the body.
    @Test
    void resumeShouldRejectNullValuedUnrecognizedField() {
        Response response =
                given().contentType(ContentType.JSON)
                        .body("{\"approved\":null}")
                        .when()
                        .post("/api/v1/executions/exec-1/resume");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).contains("approved");
    }

    @Test
    void resumeShouldRejectDecisionWithoutCorrelationId() {
        Response response =
                given().contentType(ContentType.JSON)
                        .body("{\"decision\":\"approve\"}")
                        .when()
                        .post("/api/v1/executions/exec-1/resume");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).containsIgnoringCase("correlationId");
    }

    // --- Workflow endpoints ---

    @Test
    void workflowShouldRejectNullBody() {
        Response response = given().contentType(ContentType.JSON).when().post("/api/v1/workflows");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
    }

    @Test
    void workflowShouldRejectSqlInjectionInWorkflowId() {
        Response response = given().when().get("/api/v1/workflows/; DROP TABLE");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).contains("valid identifier");
    }

    // --- Relay protocol endpoints ---

    @Test
    void relayCompletionShouldRejectEmptyBody() {
        Response response =
                given().contentType(ContentType.JSON)
                        .body("")
                        .when()
                        .post("/mcp/v1/tasks/00000000-0000-0000-0000-000000000000/complete");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
    }

    @Test
    void relayCompletionShouldRejectABodyWithNeitherResultNorError() {
        Response response =
                given().contentType(ContentType.JSON)
                        .body("{\"value\":1}")
                        .when()
                        .post("/mcp/v1/tasks/00000000-0000-0000-0000-000000000000/complete");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void relayAckShouldRefuseAMalformedTokenAsNotOffered() {
        Response response = given().when().post("/mcp/v1/tasks/x%3B+DROP+TABLE/ack");

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    void relayCatalogShouldRejectAMissingVersion() {
        Response response =
                given().contentType(ContentType.JSON)
                        .body("{\"tools\":[]}")
                        .when()
                        .put("/mcp/v1/catalog");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    /// The body is checked before the catalog version is: an unknown version would otherwise
    /// answer 409, so a 400 here proves the size bound ran first.
    @Test
    void relayPollShouldRejectAnOversizedBody() {
        Response response =
                given().contentType(ContentType.JSON)
                        .body("{\"catalogVersion\":\"" + "v".repeat(5000) + "\"}")
                        .when()
                        .post("/mcp/v1/poll");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).contains("maximum allowed size");
    }

    @Test
    void relayPollShouldRejectMalformedJson() {
        Response response =
                given().contentType(ContentType.JSON).body("{not json").when().post("/mcp/v1/poll");

        assertThat(response.statusCode()).isEqualTo(400);
    }
}
