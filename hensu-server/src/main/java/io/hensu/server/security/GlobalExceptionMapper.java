package io.hensu.server.security;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import org.jboss.logging.Logger;

/// Global exception mapper that prevents stack trace leakage to clients.
///
/// Catches all unhandled exceptions and returns sanitized JSON responses.
/// Full stack traces are logged server-side for debugging.
///
/// ### Response Format
/// ```json
/// {"error": "Human-readable message", "status": 500}
/// ```
///
/// @implNote Thread-safe. Stateless.
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof WebApplicationException wae) {
            int status = wae.getResponse().getStatus();
            String message = sanitize(status, wae.getMessage());

            if (status >= 500) {
                LOG.errorv(exception, "Server error: {0}", message);
            } else {
                LOG.debugv("Client error {0}: {1}", status, message);
            }

            return Response.status(status)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", message, "status", status))
                    .build();
        }

        LOG.errorv(exception, "Unhandled exception: {0}", exception.getMessage());

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Internal server error", "status", 500))
                .build();
    }

    private static String sanitize(int status, String raw) {
        return switch (status) {
            case 400 -> raw != null ? raw : "Bad request";
            case 401 -> "Authentication required";
            case 403 -> "Access denied";
            case 404 -> "Resource not found";
            case 405 -> "Method not allowed";
            case 409 -> "Conflict";
            case 415 -> "Unsupported media type";
            default -> {
                if (status >= 500) yield "Internal server error";
                yield raw != null ? raw : "Request failed";
            }
        };
    }
}
