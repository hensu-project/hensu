package io.hensu.core.util;

/// POSIX shell escaping for safe command interpolation.
public final class ShellEscaper {

    private ShellEscaper() {}

    /// Wraps a value in single quotes, escaping any embedded single quotes.
    /// Result is safe to interpolate into a `/bin/sh -c` command string.
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
