package io.hensu.server.validation;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/// Shared input validation predicates for the REST API boundary.
///
/// Centralizes the safe-ID pattern and dangerous-character detection
/// so that all validators and resources use the same rules.
///
/// ### Safe Identifiers
/// Identifiers must start with an alphanumeric character and contain
/// only alphanumeric characters, dots, hyphens, and underscores
/// (max 255 characters). This prevents path traversal, injection,
/// and other input-based attacks.
///
/// ### Dangerous Control Characters
/// Null bytes and non-printable control characters
/// (U+0000–U+0008, U+000B, U+000C, U+000E–U+001F, U+007F)
/// are rejected. TAB, LF, and CR are permitted in free-text fields.
///
/// @see ValidIdValidator
/// @see ValidWorkflowValidator
public final class InputValidator {

    /// Safe identifier pattern: starts with alphanumeric, up to 255 chars total.
    static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}");

    /// Control characters that are never legitimate in user content.
    /// Excludes TAB (0x09), LF (0x0A), CR (0x0D) which are valid in free text.
    static final Pattern DANGEROUS_CONTROL =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /// Maximum allowed JSON message body size in bytes (1 MB).
    public static final int MAX_JSON_MESSAGE_BYTES = 1_048_576;

    private InputValidator() {}

    /// Checks whether the value is a valid safe identifier.
    ///
    /// @param value the string to check, may be null
    /// @return {@code true} if the value matches the safe-ID pattern
    public static boolean isSafeId(String value) {
        return value != null && !value.isBlank() && SAFE_ID.matcher(value).matches();
    }

    /// Checks whether the value contains dangerous control characters.
    ///
    /// @param value the string to check, may be null
    /// @return {@code true} if the value contains illegal control characters
    public static boolean containsDangerousChars(String value) {
        return value != null && DANGEROUS_CONTROL.matcher(value).find();
    }

    /// Checks whether the value exceeds the given byte-size limit.
    ///
    /// @param value    the string to measure, may be null
    /// @param maxBytes the maximum allowed size in bytes
    /// @return {@code true} if the value exceeds the limit
    public static boolean exceedsSizeLimit(String value, int maxBytes) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length > maxBytes;
    }
}
