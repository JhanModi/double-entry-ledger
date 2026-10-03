package io.github.jhanmodi.ledger.transfers;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A key the client chooses for a money-moving request, so a retry can never move the money twice (ADR-0019). Unique
 * per client. 1 to 255 characters from {@code A-Z a-z 0-9 _ . : -}, the same rule as the database CHECK, so UUIDs and
 * keys like {@code order-123:attempt_2} work, and control characters and spaces can't reach the logs.
 */
public record IdempotencyKey(String value) {

    /** The format, for validating the {@code Idempotency-Key} header before a key is built from it. */
    public static final String FORMAT_REGEX = "[A-Za-z0-9_.:-]{1,255}";

    private static final Pattern FORMAT = Pattern.compile(FORMAT_REGEX);

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        // The message doesn't repeat the value: it's client input that failed validation.
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "an idempotency key must be 1 to 255 characters from A-Z a-z 0-9 _ . : -");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
