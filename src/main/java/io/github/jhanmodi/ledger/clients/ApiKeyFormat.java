package io.github.jhanmodi.ledger.clients;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The API key format, {@code dbl_<key id>_<secret>}, always exactly 64 characters (ADR-0016):
 *
 * <ul>
 *   <li>{@code dbl_}: a fixed prefix, so secret scanners can recognise a leaked key.
 *   <li>key id: 16 lowercase hex characters (64 random bits). Public; it only says which key this is.
 *   <li>secret: 43 base64url characters (256 random bits). Only its SHA-256 hash is ever stored.
 * </ul>
 *
 * <p>Parsing uses fixed positions rather than splitting on {@code _}, because {@code _} is also a base64url character
 * and can appear inside the secret.
 */
final class ApiKeyFormat {

    static final String PREFIX = "dbl_";
    static final int KEY_ID_LENGTH = 16;
    static final int SECRET_LENGTH = 43;
    static final int KEY_LENGTH = PREFIX.length() + KEY_ID_LENGTH + 1 + SECRET_LENGTH;

    private static final int SEPARATOR_INDEX = PREFIX.length() + KEY_ID_LENGTH;
    private static final int SECRET_BYTES = 32;
    private static final HexFormat LOWERCASE_HEX = HexFormat.of();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private ApiKeyFormat() {}

    static GeneratedKey generate(SecureRandom random) {
        byte[] keyId = new byte[KEY_ID_LENGTH / 2];
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(keyId);
        random.nextBytes(secret);
        return new GeneratedKey(LOWERCASE_HEX.formatHex(keyId), BASE64URL.encodeToString(secret));
    }

    /** Splits a presented key into its parts, or returns empty if it isn't shaped like a key at all. */
    static Optional<ParsedKey> parse(String presented) {
        if (presented == null
                || presented.length() != KEY_LENGTH
                || !presented.startsWith(PREFIX)
                || presented.charAt(SEPARATOR_INDEX) != '_') {
            return Optional.empty();
        }
        String keyId = presented.substring(PREFIX.length(), SEPARATOR_INDEX);
        String secret = presented.substring(SEPARATOR_INDEX + 1);
        if (!keyId.chars().allMatch(ApiKeyFormat::isLowercaseHex)
                || !secret.chars().allMatch(ApiKeyFormat::isBase64Url)) {
            return Optional.empty();
        }
        return Optional.of(new ParsedKey(keyId, secret));
    }

    /**
     * SHA-256 of the secret's characters. A fast hash is enough because the secret is 256 random bits, far beyond
     * brute force; slow hashes like argon2id exist for low-entropy human passwords (ADR-0011). Hashing the text, not
     * the decoded bytes, means only the exact string that was issued ever matches.
     */
    static byte[] hashSecret(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime supports SHA-256", e);
        }
    }

    private static boolean isLowercaseHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    private static boolean isBase64Url(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    record GeneratedKey(String keyId, String secret) {

        String plaintext() {
            return PREFIX + keyId + "_" + secret;
        }

        @Override
        public String toString() {
            return "GeneratedKey[keyId=" + keyId + ", secret=<hidden>]";
        }
    }

    record ParsedKey(String keyId, String secret) {

        @Override
        public String toString() {
            return "ParsedKey[keyId=" + keyId + ", secret=<hidden>]";
        }
    }
}
