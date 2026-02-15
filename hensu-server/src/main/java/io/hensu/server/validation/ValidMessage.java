package io.hensu.server.validation;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/// Validates that a raw message body is safe for processing.
///
/// A valid message:
/// - Is not null or blank
/// - Does not exceed {@link #maxBytes()} in UTF-8 size
/// - Contains no dangerous control characters
///   (U+0000–U+0008, U+000B, U+000C, U+000E–U+001F, U+007F)
///
/// Apply to raw {@code String} body parameters at REST endpoints
/// to enforce input safety at the API boundary.
///
/// ### Usage
/// ```java
/// @POST
/// @Consumes(MediaType.APPLICATION_JSON)
/// public Uni<Response> receive(@ValidMessage String body) { ... }
///
/// // Custom size limit:
/// public Uni<Response> receive(@ValidMessage(maxBytes = 65_536) String body) { ... }
/// ```
///
/// @see ValidMessageValidator
/// @see InputValidator
@Target({FIELD, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
@Constraint(validatedBy = ValidMessageValidator.class)
@Documented
public @interface ValidMessage {

    String message() default "invalid message body";

    /// Maximum allowed UTF-8 byte size. Defaults to 1 MB.
    int maxBytes() default InputValidator.MAX_JSON_MESSAGE_BYTES;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
