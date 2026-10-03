package io.github.jhanmodi.ledger.idempotency;

/**
 * The operations that take an idempotency key. A key belongs to one operation: reusing a transfer's key for a funding is
 * refused (ADR-0023). The names are stored in {@code idempotency_keys.operation}, whose CHECK lists exactly these.
 */
public enum IdempotentOperation {
    TRANSFER,
    FUNDING
}
