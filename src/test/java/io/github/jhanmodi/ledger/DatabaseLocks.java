package io.github.jhanmodi.ledger;

import io.github.jhanmodi.ledger.ledger.AccountId;
import java.sql.SQLException;
import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Questions a race test asks Postgres about locks, as the owner, so it never has to guess at timing. */
public final class DatabaseLocks {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private DatabaseLocks() {}

    /**
     * Waits until some database session is blocked waiting for a lock. Polls a condition Postgres reports, rather than
     * sleeping for a guessed time, so it's as fast as the database and fails clearly if the wait never happens.
     */
    public static void awaitASessionWaitingForALock(OwnerDatabase owner) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (owner.jdbc()
                        .sql("SELECT count(*) FROM pg_locks WHERE NOT granted")
                        .query(Long.class)
                        .single()
                == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no session started waiting for a lock within " + WAIT_LIMIT);
            }
            Thread.onSpinWait();
        }
    }

    /** Takes the row lock a posting takes on an account ({@code FOR NO KEY UPDATE}), in the caller's transaction. */
    public static void lockAccount(JdbcClient sql, AccountId account) {
        sql.sql("SELECT id FROM accounts WHERE id = :id FOR NO KEY UPDATE")
                .param("id", account.value())
                .query()
                .listOfRows();
    }

    /**
     * Whether another transaction holds a row lock on this account right now. Asks for the same kind of lock with
     * {@code NOWAIT}, which fails at once (SQLSTATE 55P03) instead of waiting. If the lock is free, it's released
     * straight away: the statement runs in a transaction of its own.
     */
    public static boolean isLocked(OwnerDatabase owner, AccountId account) {
        try {
            owner.jdbc()
                    .sql("SELECT id FROM accounts WHERE id = :id FOR NO KEY UPDATE NOWAIT")
                    .param("id", account.value())
                    .query()
                    .listOfRows();
            return false;
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
            throw e;
        }
    }
}
