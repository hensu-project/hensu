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
/// Provides shared HTTP client utilities and server/token options.
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
            names = {"--token"},
            description = "JWT bearer token for server authentication")
    private String token;

    @Inject
    @ConfigProperty(name = "hensu.server.url", defaultValue = "http://localhost:8080")
    private Optional<String> defaultServerUrl;

    @Inject
    @ConfigProperty(name = "hensu.server.token", defaultValue = "")
    private Optional<String> defaultToken;

    /// Returns the effective server base URL.
    protected String getServerUrl() {
        if (serverUrl != null && !serverUrl.isBlank()) {
            return serverUrl;
        }
        return defaultServerUrl.orElse("http://localhost:8080");
    }

    /// Returns the effective JWT bearer token, or empty if none configured.
    protected Optional<String> getToken() {
        if (token != null && !token.isBlank()) {
            return Optional.of(token);
        }
        return defaultToken.filter(t -> !t.isBlank());
    }

    /// Sends an HTTP POST request.
    ///
    /// @param path API path (e.g., "/api/v1/workflows"), not null
    /// @param jsonBody JSON request body, not null
    /// @return HTTP response, never null
    /// @throws RuntimeException if the request fails
    protected HttpResponse<String> httpPost(String path, String jsonBody) {
        var builder =
                HttpRequest.newBuilder()
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .uri(URI.create(getServerUrl() + path))
                        .header("Content-Type", "application/json");
        getToken().ifPresent(t -> builder.header("Authorization", "Bearer " + t));
        return sendRequest(builder.build());
    }

    /// Sends an HTTP GET request.
    ///
    /// @param path API path, not null
    /// @return HTTP response, never null
    /// @throws RuntimeException if the request fails
    protected HttpResponse<String> httpGet(String path) {
        var builder =
                HttpRequest.newBuilder()
                        .GET()
                        .uri(URI.create(getServerUrl() + path))
                        .header("Accept", "application/json");
        getToken().ifPresent(t -> builder.header("Authorization", "Bearer " + t));
        return sendRequest(builder.build());
    }

    /// Sends an HTTP DELETE request.
    ///
    /// @param path API path, not null
    /// @return HTTP response, never null
    /// @throws RuntimeException if the request fails
    protected HttpResponse<String> httpDelete(String path) {
        var builder = HttpRequest.newBuilder().DELETE().uri(URI.create(getServerUrl() + path));
        getToken().ifPresent(t -> builder.header("Authorization", "Bearer " + t));
        return sendRequest(builder.build());
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
                    case 401 ->
                            "Authentication required — provide a JWT via --token or hensu.server.token";
                    case 403 -> "Access denied — JWT missing required tenant_id claim";
                    case 404 -> "Not found";
                    case 500 -> "Server error: " + body;
                    default -> "HTTP " + statusCode + ": " + body;
                };
        System.err.println("Error: " + message);
    }
}
