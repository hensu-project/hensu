package io.hensu.server.validation;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/// Validates that a string, when present, is a well-formed idempotency key.
///
/// A valid key:
/// - Is 1 to 255 characters long
/// - Contains only alphanumeric characters, dots, colons, hyphens, and underscores
///
/// The key is optional: null passes, because a request without one simply is not
/// deduplicated. Pair with `@NotNull` where a key is mandatory.
///
/// ### Usage
/// ```java
/// @POST
/// public Response start(@HeaderParam("Idempotency-Key") @ValidIdempotencyKey String key) { ... }
/// ```
///
/// @see ValidIdempotencyKeyValidator
/// @see InputValidator#isIdempotencyKey(String)
@Target({FIELD, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
@Constraint(validatedBy = ValidIdempotencyKeyValidator.class)
@Documented
public @interface ValidIdempotencyKey {

    String message() default "must be 1-255 characters from [A-Za-z0-9._:-]";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
