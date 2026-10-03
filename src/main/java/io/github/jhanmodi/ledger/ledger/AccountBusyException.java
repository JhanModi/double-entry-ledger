package io.github.jhanmodi.ledger.ledger;

/**
 * Other transactions were using the same accounts, so this one gave up rather than keep waiting (ADR-0005, ADR-0022):
 * either a lock wait lasted longer than the lock timeout, or Postgres aborted every attempt to break a deadlock. Nothing
 * was posted, and the request can safely be retried after a pause.
 */
public final class AccountBusyException extends RuntimeException {

    private AccountBusyException(String message, Throwable cause) {
        super(message, cause);
    }

    /** A lock wait lasted longer than the lock timeout (SQLSTATE 55P03). */
    public static AccountBusyException lockTimedOut(Throwable cause) {
        return new AccountBusyException(
                "an account was locked by another transaction for longer than the lock timeout", cause);
    }

    /** Postgres aborted the transaction on every attempt, to break a deadlock or a serialization conflict. */
    public static AccountBusyException retriesExhausted(Throwable cause) {
        return new AccountBusyException(
                "the transaction was aborted on each of its " + RetryingTransactions.MAX_ATTEMPTS + " attempts", cause);
    }
}
