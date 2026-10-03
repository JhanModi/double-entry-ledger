package io.github.jhanmodi.ledger.ledger;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a database transaction, and runs it again if Postgres aborted the transaction to break a deadlock
 * (ADR-0022). Services that move money start their transactions here instead of with a plain
 * {@link TransactionTemplate}.
 *
 * <p>Retrying is safe because an aborted transaction leaves nothing behind: the next attempt starts from scratch, and the
 * idempotency key still guards it. The rules:
 *
 * <ul>
 *   <li><b>Only deadlocks and serialization failures are retried</b> (SQLSTATE 40P01 and 40001). Everything else, from
 *       a business error to a lost race on an idempotency key, is rethrown at once.
 *   <li><b>A lock timeout is never retried.</b> It means an account is busy, and trying again straight away adds load
 *       exactly there. It becomes {@link AccountBusyException}, and the client retries later.
 *   <li><b>At most {@value #MAX_ATTEMPTS} attempts,</b> with a short random pause before each retry. If every attempt is
 *       aborted, the result is {@link AccountBusyException} too.
 *   <li><b>Only a transaction started here is retried.</b> If the work joined a caller's transaction, that transaction
 *       is already aborted, and only the caller can start again.
 * </ul>
 */
public class RetryingTransactions {

    /** Attempts in total, the first one included. */
    static final int MAX_ATTEMPTS = 3;

    /**
     * The longest pause before the first retry. Each retry may wait up to twice as long as the one before, and the actual
     * pause is random within that limit ("full jitter"), so two transactions that deadlocked don't retry in lockstep.
     */
    static final Duration FIRST_RETRY_MAX_PAUSE = Duration.ofMillis(25);

    private static final Logger log = LoggerFactory.getLogger(RetryingTransactions.class);

    private final TransactionTemplate transactions;
    private final Sleeper sleeper;

    public RetryingTransactions(PlatformTransactionManager transactionManager) {
        this(transactionManager, Thread::sleep);
    }

    /** For unit tests, which must never really sleep. */
    RetryingTransactions(PlatformTransactionManager transactionManager, Sleeper sleeper) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.sleeper = sleeper;
    }

    /** How to wait before a retry. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Runs the work in a transaction and returns its result, retrying deadlocks as described above.
     *
     * @throws AccountBusyException a lock wait timed out, or every attempt was aborted
     */
    public <T> T execute(TransactionCallback<T> work) {
        for (int attempt = 1; ; attempt++) {
            AtomicBoolean startedHere = new AtomicBoolean();
            try {
                return transactions.execute(status -> {
                    startedHere.set(status.isNewTransaction());
                    return work.doInTransaction(status);
                });
            } catch (RuntimeException e) {
                if (!startedHere.get() || e instanceof AccountBusyException) {
                    throw e;
                }
                if (SqlState.is(e, SqlState.LOCK_NOT_AVAILABLE)) {
                    throw AccountBusyException.lockTimedOut(e);
                }
                if (!abortedByTheDatabase(e)) {
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw AccountBusyException.retriesExhausted(e);
                }
                Duration pause = pauseBeforeRetry(attempt, ThreadLocalRandom.current());
                // The request id is on this line, from the MDC, so retries can be traced to their request.
                log.warn(
                        "Postgres aborted the transaction ({}); retrying in {} ms (attempt {} of {})",
                        abortedByADeadlock(e) ? "deadlock" : "serialization failure",
                        pause.toMillis(),
                        attempt + 1,
                        MAX_ATTEMPTS);
                sleepOrGiveUp(pause, e);
            }
        }
    }

    /** A random pause between zero and the limit for this retry, which doubles each time. */
    static Duration pauseBeforeRetry(int failedAttempt, RandomGenerator random) {
        long limitMillis = FIRST_RETRY_MAX_PAUSE.toMillis() << (failedAttempt - 1);
        return Duration.ofMillis(random.nextLong(limitMillis + 1));
    }

    private static boolean abortedByTheDatabase(RuntimeException e) {
        return abortedByADeadlock(e) || SqlState.is(e, SqlState.SERIALIZATION_FAILURE);
    }

    private static boolean abortedByADeadlock(RuntimeException e) {
        return SqlState.is(e, SqlState.DEADLOCK_DETECTED);
    }

    private void sleepOrGiveUp(Duration pause, RuntimeException failure) {
        try {
            sleeper.sleep(pause);
        } catch (InterruptedException interrupted) {
            // Being interrupted means "stop" (the server is shutting down): keep the flag for the caller, and report
            // the
            // failure as it was instead of starting another attempt.
            Thread.currentThread().interrupt();
            throw failure;
        }
    }
}
