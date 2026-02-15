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

    // --- MCP endpoints ---

    @Test
    void mcpShouldRejectMissingClientId() {
        Response response = given().when().get("/mcp/connect");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).containsIgnoringCase("clientId");
    }

    @Test
    void mcpShouldRejectBlankClientId() {
        Response response = given().when().get("/mcp/connect?clientId=   ");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).containsIgnoringCase("clientId");
    }

    @Test
    void mcpShouldRejectEmptyMessageBody() {
        Response response =
                given().contentType(ContentType.JSON).body("").when().post("/mcp/message");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
    }

    @Test
    void mcpShouldRejectSpecialCharsInClientStatusId() {
        Response response = given().when().get("/mcp/clients/client%3B+DROP+TABLE/status");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).contains("valid identifier");
    }
}
