package io.github.jhanmodi.ledger.idempotency;

import java.util.Objects;

/**
 * What an operation that takes an idempotency key returns (ADR-0023): its result, and whether this request carried it
 * out or is replaying an earlier request's. A replay did nothing new: no money moved, and nothing was audited.
 *
 * @param replayed true if an earlier request with the same key and the same fingerprint produced this value
 */
public record IdempotentResult<T>(T value, boolean replayed) {

    public IdempotentResult {
        Objects.requireNonNull(value, "value");
    }

    /** This request carried the operation out. */
    public static <T> IdempotentResult<T> firstTime(T value) {
        return new IdempotentResult<>(value, false);
    }

    /** An earlier request with the same key and fingerprint carried the operation out; this is its result again. */
    public static <T> IdempotentResult<T> replay(T value) {
        return new IdempotentResult<>(value, true);
    }
}
