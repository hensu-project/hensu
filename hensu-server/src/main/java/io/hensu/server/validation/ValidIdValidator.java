package io.hensu.server.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/// Validates that a string matches the safe identifier pattern.
///
/// Accepts strings that start with an alphanumeric character and contain only
/// alphanumeric characters, dots, hyphens, and underscores (max 255 chars).
///
/// @see ValidId
public class ValidIdValidator implements ConstraintValidator<ValidId, String> {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}");

    /// Checks whether the given string is a valid safe identifier.
    ///
    /// @param value the string to validate, may be null
    /// @param context validator context, not null
    /// @return `true` if the value matches the safe ID pattern, `false` otherwise
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return ID_PATTERN.matcher(value).matches();
    }
}
