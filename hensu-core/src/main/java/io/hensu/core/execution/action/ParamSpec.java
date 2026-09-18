package io.hensu.core.execution.action;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/// Schema for one parameter a command accepts.
///
/// The schema is the second gate of the execution security model: the catalog
/// decides which binary may run, and the schema decides what shape its arguments
/// may take. Everything it can reject is rejected before a process exists, so a
/// malformed argument costs an agent a failed tool result rather than a launch.
///
/// ### Supported types
/// - `string` – bound as a single argv token
/// - `number` – a whole or decimal number, bound as a single token
/// - `boolean` – `true` or `false`, bound as a single token
/// - `list` – a list of scalars, bound as one token per element
///
/// ### Contracts
/// - **Precondition**: `name` is a non-blank identifier unique within its command
/// - **Postcondition**: {@link #validate} returns a message for every value the
///   schema forbids and empty for every value it permits; it never throws
/// - **Invariant**: `pattern`, `enumValues` and `maxLength` constrain the textual
///   form of a value and are ignored for `boolean`
///
/// @param name the parameter identifier as written in configuration, not null
/// @param type one of `string`, `number`, `boolean`, `list`, not null
/// @param required whether a value must be supplied
/// @param pattern regular expression every value must match fully, may be null
/// @param enumValues the closed set of permitted values, may be null (may be empty)
/// @param maxLength the longest permitted textual form, may be null
/// @param secret whether the value carries a credential and must never be audited
/// @implNote **Immutable after construction.** Safe to share across Virtual Threads.
/// @see ToolSpec for the agent-visible block carrying these
/// @see io.hensu.core.tool.ToolDefinition.ParameterDef#sensitive for the audit side of `secret`
public record ParamSpec(
        String name,
        String type,
        boolean required,
        String pattern,
        List<String> enumValues,
        Integer maxLength,
        boolean secret) {

    /// Prefix of the environment variables shell-mode commands receive parameters through.
    ///
    /// The prefix is reserved end to end: a catalog may not declare an `env:`
    /// key inside it, and the runner never forwards a host variable carrying it
    /// into a child process, so the only way a `HENSU_PARAM_*` variable reaches a
    /// command is through its own declared schema.
    public static final String ENV_PREFIX = "HENSU_PARAM_";

    /// The types a parameter may declare.
    public static final List<String> TYPES = List.of("string", "number", "boolean", "list");

    /// Compact constructor with validation and defensive copies.
    ///
    /// @throws IllegalArgumentException if the name is blank or the type is unknown
    public ParamSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("param name must not be blank");
        }
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "unknown param type '" + type + "', expected one of " + TYPES);
        }
        enumValues = enumValues != null ? List.copyOf(enumValues) : List.of();
    }

    /// Creates a plain optional string parameter.
    ///
    /// @param name the parameter identifier, not null
    /// @return the parameter schema, never null
    public static ParamSpec optionalString(String name) {
        return new ParamSpec(name, "string", false, null, List.of(), null, false);
    }

    /// Returns the environment variable this parameter is exposed through in shell mode.
    ///
    /// The mapping is deterministic – {@link #ENV_PREFIX}, the name upper-cased,
    /// every character outside `[A-Za-z0-9]` replaced by an underscore – so two
    /// names that would collide are caught when the catalog loads rather than by
    /// one silently overwriting the other at run time.
    ///
    /// @return the variable name, never null
    public String environmentVariable() {
        return ENV_PREFIX + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Za-z0-9]", "_");
    }

    /// Returns whether this parameter binds to more than one argv token.
    ///
    /// @return true for `list`, false for every scalar type
    public boolean isList() {
        return "list".equals(type);
    }

    /// Checks a supplied value against this schema.
    ///
    /// @param value the value supplied for this parameter, may be null when absent
    /// @return the reason the value is rejected, or empty when it is acceptable
    public Optional<String> validate(Object value) {
        if (value == null) {
            return required
                    ? Optional.of("parameter '" + name + "' is required")
                    : Optional.empty();
        }
        if (isList()) {
            if (!(value instanceof List<?> items)) {
                return Optional.of("parameter '" + name + "' expects a list");
            }
            for (Object item : items) {
                Optional<String> failure = validateScalar(item);
                if (failure.isPresent()) {
                    return failure;
                }
            }
            return Optional.empty();
        }
        if (value instanceof List<?>) {
            return Optional.of("parameter '" + name + "' expects a single " + type);
        }
        return validateScalar(value);
    }

    private Optional<String> validateScalar(Object value) {
        if (value == null) {
            return Optional.of("parameter '" + name + "' must not contain a null value");
        }
        String text = String.valueOf(value);
        switch (type) {
            case "number" -> {
                try {
                    Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    return Optional.of(
                            "parameter '" + name + "' expects a number, got '" + text + "'");
                }
            }
            case "boolean" -> {
                if (!"true".equals(text) && !"false".equals(text)) {
                    return Optional.of(
                            "parameter '" + name + "' expects true or false, got '" + text + "'");
                }
                return Optional.empty();
            }
            default -> {
                // string and list elements carry no type constraint of their own
            }
        }
        if (maxLength != null && text.length() > maxLength) {
            return Optional.of(
                    "parameter '"
                            + name
                            + "' exceeds maxLength "
                            + maxLength
                            + " ("
                            + text.length()
                            + " characters)");
        }
        if (!enumValues.isEmpty() && !enumValues.contains(text)) {
            return Optional.of(
                    "parameter '"
                            + name
                            + "' must be one of "
                            + enumValues
                            + ", got '"
                            + text
                            + "'");
        }
        if (pattern != null && !matchesPattern(text)) {
            return Optional.of("parameter '" + name + "' does not match pattern '" + pattern + "'");
        }
        return Optional.empty();
    }

    private boolean matchesPattern(String text) {
        try {
            return Pattern.matches(pattern, text);
        } catch (PatternSyntaxException e) {
            // Unreachable in practice: the catalog compiles every pattern at load.
            return false;
        }
    }
}
