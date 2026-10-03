package io.github.jhanmodi.ledger.idempotency;

/**
 * The client already used this idempotency key for a different request: another operation, or the same operation with
 * different values (ADR-0023). Usually a bug in the client, such as a key reused for a new request. Nothing was done.
 */
public final class IdempotencyKeyReusedException extends RuntimeException {

    IdempotencyKeyReusedException() {
        super("this idempotency key was already used for a different request");
    }
}
