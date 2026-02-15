package io.hensu.server.validation;

/// Strips control characters from strings to prevent log injection.
///
/// Log injection occurs when user-controlled input containing newline
/// characters ({@code \r}, {@code \n}) is written to log output,
/// allowing attackers to forge log entries.
///
/// Apply to any user-derived value before passing it to a logger:
/// ```
/// LOG.infov("Processing: id={0}", LogSanitizer.sanitize(userInput));
/// ```
public final class LogSanitizer {

    private LogSanitizer() {}

    /// Removes carriage-return and newline characters from the input.
    ///
    /// @param value the string to sanitize, may be null
    /// @return sanitized string, or {@code "null"} if input is null
    public static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace("\r", "").replace("\n", "");
    }
}
