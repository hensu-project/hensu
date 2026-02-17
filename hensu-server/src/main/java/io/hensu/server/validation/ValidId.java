package io.hensu.server.validation;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/// Validates that a string is a safe identifier for use in path parameters,
/// query parameters, and DTO fields.
///
/// A valid identifier:
/// - Is not null or blank
/// - Starts with an alphanumeric character
/// - Contains only alphanumeric characters, dots, hyphens, and underscores
/// - Is at most 255 characters long
///
/// This prevents path traversal, SQL injection, and other input-based attacks
/// at the API boundary.
///
/// ### Usage
/// ```java
/// @GET
/// @Path("/{workflowId}")
/// public Response get(@PathParam("workflowId") @ValidId String workflowId) { ... }
///
/// public record StartRequest(@ValidId String workflowId) {}
/// ```
///
/// @see ValidIdValidator
@Target({FIELD, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
@Constraint(validatedBy = ValidIdValidator.class)
@Documented
public @interface ValidId {

    String message() default
            "must be a valid identifier (alphanumeric, dots, hyphens, underscores; 1-255 chars)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
