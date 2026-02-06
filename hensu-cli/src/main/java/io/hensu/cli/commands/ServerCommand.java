package io.hensu.cli.commands;

import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import picocli.CommandLine.Option;

/// Base class for CLI commands that interact with the Hensu server.
///
/// Provides shared HTTP client utilities and server/tenant options.
/// Extends `WorkflowCommand` to inherit banner display and working directory resolution.
///
/// @see WorkflowPushCommand
/// @see WorkflowPullCommand
/// @see WorkflowDeleteCommand
/// @see WorkflowListCommand
public abstract class ServerCommand extends WorkflowCommand {

    @Option(
            names = {"--server"},
            description = "Server URL (default: ${DEFAULT-VALUE})")
    private String serverUrl;

    @Option(
            names = {"--tenant"},
            description = "Tenant ID (default: ${DEFAULT-VALUE})")
    private String tenantId;

    @Inject
    @ConfigProperty(name = "hensu.server.url", defaultValue = "http://localhost:8080")
    private Optional<String> defaultServerUrl;

    @Inject
    @ConfigProperty(name = "hensu.tenant.id", defaultValue = "default")
    private Optional<String> defaultTenantId;

    /// Returns the effective server base URL.
    protected String getServerUrl() {
        if (serverUrl != null && !serverUrl.isBlank()) {
            return serverUrl;
        }
        return defaultServerUrl.orElse("http://localhost:8080");
    }

    /// Returns the effective tenant ID.
    protected String getTenantId() {
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId;
        }
        return defaultTenantId.orElse("default");
    }

    /// Sends an HTTP POST request.
    ///
    /// @param path API path (e.g., "/api/v1/workflows"), not null
    /// @param jsonBody JSON request body, not null
    /// @return HTTP response, never null
    /// @throws RuntimeException if the request fails
    protected HttpResponse<String> httpPost(String path, String jsonBody) {
        return sendRequest(
                HttpRequest.newBuilder()
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .uri(URI.create(getServerUrl() + path))
                        .header("Content-Type", "application/json")
                        .header("X-Tenant-ID", getTenantId())
                        .build());
    }

    /// Sends an HTTP GET request.
    ///
    /// @param path API path, not null
    /// @return HTTP response, never null
    /// @throws RuntimeException if the request fails
    protected HttpResponse<String> httpGet(String path) {
        return sendRequest(
                HttpRequest.newBuilder()
                        .GET()
                        .uri(URI.create(getServerUrl() + path))
                        .header("Accept", "application/json")
                        .header("X-Tenant-ID", getTenantId())
                        .build());
    }

    /// Sends an HTTP DELETE request.
    ///
    /// @param path API path, not null
    /// @return HTTP response, never null
    /// @throws RuntimeException if the request fails
    protected HttpResponse<String> httpDelete(String path) {
        return sendRequest(
                HttpRequest.newBuilder()
                        .DELETE()
                        .uri(URI.create(getServerUrl() + path))
                        .header("X-Tenant-ID", getTenantId())
                        .build());
    }

    private HttpResponse<String> sendRequest(HttpRequest request) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to connect to server at " + getServerUrl() + ": " + e.getMessage(), e);
        }
    }

    /// Prints a server error message based on HTTP status code.
    protected void printHttpError(int statusCode, String body) {
        String message =
                switch (statusCode) {
                    case 400 -> "Bad request: " + body;
                    case 404 -> "Not found";
                    case 500 -> "Server error: " + body;
                    default -> "HTTP " + statusCode + ": " + body;
                };
        System.err.println("Error: " + message);
    }
}
