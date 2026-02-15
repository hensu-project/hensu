package io.hensu.server.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/// Validates that a string matches the safe identifier pattern.
///
/// Delegates to {@link InputValidator#isSafeId(String)} for the actual check.
///
/// @see ValidId
/// @see InputValidator
public class ValidIdValidator implements ConstraintValidator<ValidId, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return InputValidator.isSafeId(value);
    }
}
