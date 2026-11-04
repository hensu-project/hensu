package io.hensu.server.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/// Validates that an optional idempotency key, when present, matches the key pattern.
///
/// Delegates to {@link InputValidator#isIdempotencyKey(String)} for the actual check.
///
/// @see ValidIdempotencyKey
/// @see InputValidator
public class ValidIdempotencyKeyValidator
        implements ConstraintValidator<ValidIdempotencyKey, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || InputValidator.isIdempotencyKey(value);
    }
}
