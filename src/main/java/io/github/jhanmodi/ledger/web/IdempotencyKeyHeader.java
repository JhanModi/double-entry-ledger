package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.idempotency.IdempotentResult;
import org.springframework.http.ResponseEntity;

/**
 * The idempotency headers (ADR-0023). Every money-moving POST requires {@code Idempotency-Key}: controllers validate it
 * with {@code @Pattern(regexp = IdempotencyKey.FORMAT_REGEX)}, so a missing or malformed key is a 400 before anything
 * else happens. A response that replays an earlier request's result carries {@code Idempotent-Replayed: true}.
 */
final class IdempotencyKeyHeader {

    static final String NAME = "Idempotency-Key";

    /**
     * Set on a replay, so a client (or its logs) can tell that this request did nothing new. Absent otherwise. The
     * status, {@code Location}, and body are the original's either way.
     */
    static final String REPLAYED = "Idempotent-Replayed";

    private IdempotencyKeyHeader() {}

    /** Adds {@link #REPLAYED} to the response if the result is a replay. */
    static ResponseEntity.BodyBuilder markIfReplayed(ResponseEntity.BodyBuilder response, IdempotentResult<?> result) {
        if (result.replayed()) {
            response.header(REPLAYED, "true");
        }
        return response;
    }
}
