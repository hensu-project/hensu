package io.hensu.server.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/// Maps Bean Validation constraint violations to HTTP 400 JSON responses.
///
/// Overrides the built-in Quarkus mapper to produce responses consistent
/// with the standard error format used by
/// {@link io.hensu.server.security.GlobalExceptionMapper}.
///
/// ### Response Format
/// ```json
/// {"error": "workflowId: workflowId is required", "status": 400}
/// ```
///
/// @implNote Thread-safe. Stateless.
/// @see ValidId
/// @see io.hensu.server.security.GlobalExceptionMapper
@Provider
public class ConstraintViolationExceptionMapper
        implements ExceptionMapper<ConstraintViolationException> {

    private static final Logger LOG = Logger.getLogger(ConstraintViolationExceptionMapper.class);

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        String message =
                exception.getConstraintViolations().stream()
                        .map(v -> extractParamName(v) + ": " + v.getMessage())
                        .collect(Collectors.joining("; "));

        LOG.debugv("Validation error: {0}", message);

        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", message, "status", 400))
                .build();
    }

    /// Extracts the leaf parameter name from a constraint violation property path.
    ///
    /// For method parameters the path is `methodName.paramName` — returns `paramName`.
    /// For bean fields the path is `fieldName` — returned as-is.
    ///
    /// @param violation the constraint violation, not null
    /// @return leaf parameter name, never null
    private static String extractParamName(ConstraintViolation<?> violation) {
        String name = null;
        for (Path.Node node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return name != null ? name : "unknown";
    }
}
