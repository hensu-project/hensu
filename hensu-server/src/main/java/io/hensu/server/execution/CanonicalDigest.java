package io.hensu.server.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/// A stable fingerprint of a JSON-shaped value: SHA-256 of its canonical JSON, map keys
/// sorted at every level.
///
/// Two requests are compared by it where the original may be gone:
/// - a tool intent keeps the digest of its arguments after they are cleared, so a replay
///   that reaches the same call key with different arguments is caught as a divergence
///   however long ago the original settled;
/// - a start keeps the digest of its request beside its idempotency key, so a retry is
///   told apart from a different request that reused the key.
public final class CanonicalDigest {

    private static final ObjectMapper CANONICAL =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private CanonicalDigest() {}

    /// @param value the value to fingerprint – maps, lists, strings, numbers, booleans and
    ///     nulls – not null
    /// @return the lowercase hex digest, never null
    public static String of(Map<String, Object> value) {
        try {
            byte[] json = CANONICAL.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint the value", e);
        }
    }
}
