package io.github.jhanmodi.ledger.idempotency;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A fixed-size fingerprint of a request: the SHA-256 hash of its operation and validated fields (ADR-0023, D3). The same
 * operation with the same values always has the same fingerprint, and changing any value changes it. Storing only the
 * fingerprint lets a retry be compared with the original without keeping a copy of the request.
 *
 * <p>The encoding is a contract with every stored claim: change it, and a retry that spans the deploy no longer matches
 * its original. So it only ever changes along with its version tag.
 *
 * <ul>
 *   <li><b>The items, in order:</b> the version tag {@value #VERSION}, the operation's name, then each field's name and
 *       value, in the order the command adds them. The idempotency key itself is not included.
 *   <li><b>Each item</b> is written as its length in UTF-8 bytes, a colon, and the item itself: {@code 3:USD}. The length
 *       says exactly where an item ends, so {@code "ab" + "c"} and {@code "a" + "bc"} can't produce the same text.
 *   <li><b>A missing value</b> is written as {@value #MISSING}. Every item starts with a digit, so the two can't be
 *       confused, and a missing value differs from an empty one ({@code 0:}).
 * </ul>
 *
 * <p>The hash is SHA-256 of that text's UTF-8 bytes, from Java's standard library. It isn't a secret and protects
 * nothing on its own: it's compared only within one client's own keys.
 */
public final class RequestFingerprint {

    static final String VERSION = "v1";
    static final String MISSING = "-";

    private static final int SHA_256_BYTES = 32;

    private final byte[] sha256;

    private RequestFingerprint(byte[] sha256) {
        if (sha256.length != SHA_256_BYTES) {
            throw new IllegalArgumentException("a SHA-256 hash is " + SHA_256_BYTES + " bytes, got " + sha256.length);
        }
        this.sha256 = sha256.clone();
    }

    /** Starts a fingerprint of a request to carry out this operation. */
    public static Builder of(IdempotentOperation operation) {
        return new Builder(Objects.requireNonNull(operation, "operation"));
    }

    /** A fingerprint read back from {@code idempotency_keys.request_hash}. */
    static RequestFingerprint fromBytes(byte[] sha256) {
        return new RequestFingerprint(sha256);
    }

    /** The 32 bytes, as stored in {@code idempotency_keys.request_hash}. A copy: a fingerprint never changes. */
    byte[] bytes() {
        return sha256.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RequestFingerprint fingerprint && Arrays.equals(sha256, fingerprint.sha256);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(sha256);
    }

    /** The hash in hexadecimal. Safe to log: the request's values can't be read back from it. */
    @Override
    public String toString() {
        return HexFormat.of().formatHex(sha256);
    }

    /** Adds a request's fields one by one, in the order the request defines. */
    public static final class Builder {

        private final StringBuilder text = new StringBuilder();

        private Builder(IdempotentOperation operation) {
            item(VERSION);
            item(operation.name());
        }

        /** Adds one field. A null value is written as missing, which differs from an empty string. */
        public Builder field(String name, @Nullable String value) {
            item(Objects.requireNonNull(name, "name"));
            if (value == null) {
                text.append(MISSING);
            } else {
                item(value);
            }
            return this;
        }

        public RequestFingerprint build() {
            return new RequestFingerprint(sha256(canonicalText()));
        }

        /** The exact text that's hashed. For tests that pin the encoding. */
        String canonicalText() {
            return text.toString();
        }

        private void item(String value) {
            text.append(value.getBytes(UTF_8).length).append(':').append(value);
        }

        private static byte[] sha256(String text) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8));
            } catch (NoSuchAlgorithmException e) {
                // Every Java runtime is required to provide SHA-256, so this means a broken runtime.
                throw new IllegalStateException("SHA-256 is not available", e);
            }
        }
    }
}
