package io.hensu.server.validation;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/// Validates that a {@link io.hensu.core.workflow.Workflow} object is safe
/// for processing by the server.
///
/// Checks the entire object graph:
/// - All identifier fields match the safe-ID pattern
/// - Free-text fields are free of dangerous control characters
///
/// ### Usage
/// ```java
/// @POST
/// public Response push(@ValidWorkflow Workflow workflow) { ... }
/// ```
///
/// @see ValidWorkflowValidator
/// @see ValidId
@Target({PARAMETER, FIELD})
@Retention(RUNTIME)
@Constraint(validatedBy = ValidWorkflowValidator.class)
@Documented
public @interface ValidWorkflow {

    String message() default "workflow contains invalid or unsafe content";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
