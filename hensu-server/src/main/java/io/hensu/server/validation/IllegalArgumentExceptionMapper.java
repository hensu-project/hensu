package io.hensu.server.validation;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import org.jboss.logging.Logger;

/// Maps {@link IllegalArgumentException} to HTTP 400 JSON responses.
///
/// Catches validation failures thrown by domain types (e.g.
/// {@link io.hensu.core.state.ExecutionPhase#validateCorrelation}) and
/// API-layer input parsing before they surface as HTTP 500.
///
/// ### Response Format
/// ```json
/// {"error": "Correlation id mismatch: expected 'abc', got 'xyz'", "status": 400}
/// ```
///
/// @implNote Thread-safe. Stateless.
/// @see ConstraintViolationExceptionMapper
/// @see io.hensu.server.security.GlobalExceptionMapper
@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException> {

    private static final Logger LOG = Logger.getLogger(IllegalArgumentExceptionMapper.class);

    @Override
    public Response toResponse(IllegalArgumentException exception) {
        LOG.debugv("Bad request: {0}", exception.getMessage());

        String safeMessage = sanitize(exception.getMessage());
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", safeMessage, "status", 400))
                .build();
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "Invalid request";
        }
        if (message.length() > 200) {
            return message.substring(0, 200);
        }
        return message;
    }
}
