package io.hensu.server.multinode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

/// Mints RS256 bearer tokens the servers accept, with nothing but the JDK.
///
/// The servers verify signature, issuer and expiry, and read the tenant from the
/// `tenant_id` claim – the same binding a production deployment relies on, so the
/// test exercises tenant isolation instead of switching authentication off.
final class Jwt {

    static final String ISSUER = "https://hensu.io";

    /// Name of the public key file {@link #writePublicKey} writes, as the servers look it up.
    static final String PUBLIC_KEY_FILE = "public.pem";

    private final KeyPair keys;

    Jwt() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keys = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot generate the fault-test signing key", e);
        }
    }

    /// Writes the public key where the server containers read it.
    ///
    /// @param dir directory mounted into every server container, not null
    void writePublicKey(Path dir) throws Exception {
        Files.createDirectories(dir);
        String pem =
                "-----BEGIN PUBLIC KEY-----\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                                .encodeToString(keys.getPublic().getEncoded())
                        + "\n-----END PUBLIC KEY-----\n";
        Files.writeString(dir.resolve(PUBLIC_KEY_FILE), pem);
    }

    /// Returns a token for `tenant` valid for six hours.
    String token(String tenant) {
        long now = Instant.now().getEpochSecond();
        String header = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload =
                encode(
                        "{\"iss\":\""
                                + ISSUER
                                + "\",\"sub\":\"fault-test-"
                                + tenant
                                + "\",\"upn\":\"fault-test-"
                                + tenant
                                + "\",\"tenant_id\":\""
                                + tenant
                                + "\",\"iat\":"
                                + (now - 300)
                                + ",\"exp\":"
                                + (now + 6 * 3600)
                                + "}");
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keys.getPrivate());
            signature.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header
                    + "."
                    + payload
                    + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign a fault-test token", e);
        }
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
