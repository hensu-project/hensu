package io.hensu.core.util;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/// Validates LLM-generated node output before it is stored in workflow state.
///
/// LLM outputs are non-deterministic and treated as untrusted data. This validator
/// applies defense-in-depth checks tailored to the specific threats posed by agentic
/// outputs, which differ from user-supplied REST input in both scope and character set.
///
/// ### Checks Provided
/// - **ASCII control characters** — null bytes and non-printable chars that degrade
///   downstream text processing ({@link #containsDangerousChars(String)})
/// - **Unicode manipulation characters** — invisible and directional Unicode chars
///   that can hide content, weaponize prompt injection, or corrupt downstream display
///   ({@link #containsUnicodeTricks(String)})
/// - **Payload size** — oversized outputs that could exhaust memory or storage
///   ({@link #exceedsSizeLimit(String, int)} with {@link #MAX_LLM_OUTPUT_BYTES})
///
/// ### Relationship to Server-Side Validation
/// This class is intentionally separate from `InputValidator` in `hensu-server`.
/// REST input and LLM output are distinct trust boundaries with different threat models:
/// REST input is short, user-typed, and identity-validated; LLM output is long,
/// machine-generated, and carries Unicode-level injection risks that REST input does not.
///
/// @implNote Stateless utility class. All methods are pure functions — no side effects,
/// no shared mutable state. Safe to call from any thread.
///
/// @see io.hensu.core.execution.pipeline.OutputExtractionPostProcessor
public final class AgentOutputValidator {

    /// ASCII control characters that are never legitimate in agent output.
    /// Excludes TAB (0x09), LF (0x0A), CR (0x0D) which are valid in free text.
    static final Pattern DANGEROUS_CONTROL =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /// Unicode characters that can be used for visual deception or prompt injection.
    ///
    /// Covers:
    /// - **RTL/LTR directional overrides**: U+202A–U+202E (LRE, RLE, PDF, LRO, RLO)
    /// - **Unicode isolates**: U+2066–U+2069 (LRI, RLI, FSI, PDI)
    /// - **Zero-width chars**: U+200B (ZWSP), U+200C (ZWNJ), U+200D (ZWJ)
    /// - **Byte-order mark**: U+FEFF (BOM)
    static final Pattern UNICODE_TRICKS =
            Pattern.compile("[\u202A-\u202E\u2066-\u2069\u200B-\u200D\uFEFF]");

    /// Maximum allowed LLM node output size in bytes (4 MB).
    ///
    /// Set higher than the REST input cap to accommodate legitimately large agentic
    /// outputs such as long documents, generated code, or research summaries.
    /// Outputs exceeding this limit indicate runaway generation and are rejected
    /// to protect downstream pipeline stages from memory exhaustion.
    public static final int MAX_LLM_OUTPUT_BYTES = 4_194_304;

    private AgentOutputValidator() {}

    /// Checks whether the value contains dangerous ASCII control characters.
    ///
    /// Detects null bytes and non-printable control characters
    /// (U+0000–U+0008, U+000B, U+000C, U+000E–U+001F, U+007F).
    /// TAB, LF, and CR are not considered dangerous.
    ///
    /// @param value the string to check, may be null
    /// @return `true` if the value contains illegal control characters;
    ///         `false` if null or clean
    public static boolean containsDangerousChars(String value) {
        return value != null && DANGEROUS_CONTROL.matcher(value).find();
    }

    /// Checks whether the value contains Unicode characters used for visual manipulation.
    ///
    /// Detects bidirectional override characters, Unicode isolates, zero-width characters,
    /// and byte-order marks that can be used to hide malicious content, redirect text
    /// rendering, or carry covert prompt injection payloads in agentic outputs.
    ///
    /// @param value the string to check, may be null
    /// @return `true` if the value contains Unicode manipulation characters;
    ///         `false` if null or clean
    public static boolean containsUnicodeTricks(String value) {
        return value != null && UNICODE_TRICKS.matcher(value).find();
    }

    /// Checks whether the value exceeds the given byte-size limit (UTF-8 encoded).
    ///
    /// @param value    the string to measure, may be null
    /// @param maxBytes the maximum allowed size in bytes, must be positive
    /// @return `true` if the UTF-8 byte length of `value` exceeds `maxBytes`;
    ///         `false` if null or within limit
    public static boolean exceedsSizeLimit(String value, int maxBytes) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length > maxBytes;
    }
}
