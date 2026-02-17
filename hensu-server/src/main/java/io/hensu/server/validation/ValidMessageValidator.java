package io.hensu.server.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/// Validates that a raw message body is safe for processing.
///
/// Checks three conditions using {@link InputValidator}:
/// 1. Not null or blank
/// 2. UTF-8 byte size within the configured limit
/// 3. No dangerous control characters
///
/// Each failing condition produces a distinct violation message
/// to aid client-side error handling.
///
/// @see ValidMessage
/// @see InputValidator
public class ValidMessageValidator implements ConstraintValidator<ValidMessage, String> {

    private int maxBytes;

    @Override
    public void initialize(ValidMessage annotation) {
        this.maxBytes = annotation.maxBytes();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null || value.isBlank()) {
            replaceMessage(ctx, "Message body is required");
            return false;
        }

        if (InputValidator.exceedsSizeLimit(value, maxBytes)) {
            replaceMessage(ctx, "Message exceeds maximum allowed size");
            return false;
        }

        if (InputValidator.containsDangerousChars(value)) {
            replaceMessage(ctx, "Message contains illegal control characters");
            return false;
        }

        return true;
    }

    private static void replaceMessage(ConstraintValidatorContext ctx, String message) {
        ctx.disableDefaultConstraintViolation();
        ctx.buildConstraintViolationWithTemplate(message).addConstraintViolation();
    }
}
