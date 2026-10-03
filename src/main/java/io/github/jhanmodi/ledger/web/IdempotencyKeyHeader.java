package io.github.jhanmodi.ledger.web;

/**
 * The {@code Idempotency-Key} header that every money-moving POST requires (ADR-0019). Controllers validate it with
 * {@code @Pattern(regexp = IdempotencyKey.FORMAT_REGEX)}, so a missing or malformed key is a 400 before anything else
 * happens.
 */
final class IdempotencyKeyHeader {

    static final String NAME = "Idempotency-Key";

    private IdempotencyKeyHeader() {}
}
