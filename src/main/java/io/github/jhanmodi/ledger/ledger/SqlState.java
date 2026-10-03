package io.github.jhanmodi.ledger.ledger;

import java.sql.SQLException;

/**
 * Postgres error codes (SQLSTATE) the ledger and the idempotency keys react to. Errors are recognized by these codes,
 * which Postgres documents and keeps stable, rather than by the wording of the message or by which Spring exception
 * class wraps them.
 */
public final class SqlState {

    /** A lock wait lasted longer than {@code lock_timeout} (or a {@code NOWAIT} lock was taken). */
    public static final String LOCK_NOT_AVAILABLE = "55P03";

    /** Postgres found a deadlock and aborted this transaction to break it. */
    static final String DEADLOCK_DETECTED = "40P01";

    /** Postgres aborted this transaction because it conflicted with a concurrent one. Can't happen at READ COMMITTED. */
    static final String SERIALIZATION_FAILURE = "40001";

    private SqlState() {}

    /** Whether this exception, or any exception that caused it, is a database error with this code. */
    public static boolean is(Throwable e, String code) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && code.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
