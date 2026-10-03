package io.github.jhanmodi.ledger.idempotency;

/**
 * Another request with the same idempotency key was still running when this one's wait for it ran out (ADR-0023, D4).
 * This request did nothing. The other one may still succeed or fail, so retrying later with the same key returns its
 * result, or carries the request out if it failed.
 *
 * <p>Deliberately carries no cause. The database error behind it is a lock timeout (SQLSTATE 55P03), and
 * {@code RetryingTransactions} looks for that code anywhere in an exception's causes: it would report this as a busy
 * account instead (ADR-0022).
 */
public final class RequestInProgressException extends RuntimeException {

    RequestInProgressException() {
        super("another request with this idempotency key was still running when the lock timeout ran out");
    }
}
