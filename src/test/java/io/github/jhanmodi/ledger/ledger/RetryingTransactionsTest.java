package io.github.jhanmodi.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Every branch of the retry rules (ADR-0022), with a fake transaction manager: no database, and no real sleeping. */
class RetryingTransactionsTest {

    private final FakeTransactionManager transactionManager = new FakeTransactionManager();
    private final List<Duration> pauses = new ArrayList<>();
    private final RetryingTransactions retrying = new RetryingTransactions(transactionManager, pauses::add);

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void aTransactionThatSucceedsRunsOnceAndCommits() {
        String result = retrying.execute(status -> "moved");

        assertThat(result).isEqualTo("moved");
        assertThat(transactionManager.log).containsExactly("begin", "commit");
        assertThat(pauses).isEmpty();
    }

    @Test
    void aDeadlockIsRetriedInAFreshTransactionAfterAPause() {
        Attempts attempts = new Attempts(deadlock());

        String result = retrying.execute(status -> attempts.next());

        assertThat(result).isEqualTo("moved");
        assertThat(transactionManager.log).containsExactly("begin", "rollback", "begin", "commit");
        assertThat(pauses).hasSize(1);
    }

    @Test
    void aSerializationFailureIsRetriedToo() {
        Attempts attempts = new Attempts(serializationFailure());

        String result = retrying.execute(status -> attempts.next());

        assertThat(result).isEqualTo("moved");
        assertThat(attempts.made).isEqualTo(2);
    }

    @Test
    void aDeadlockAtCommitIsRetried() {
        // Constraint triggers run at COMMIT, so a deadlock can surface there rather than inside the work.
        transactionManager.failCommits.add(deadlock());
        Attempts attempts = new Attempts();

        String result = retrying.execute(status -> attempts.next());

        assertThat(result).isEqualTo("moved");
        assertThat(attempts.made).isEqualTo(2);
        assertThat(transactionManager.log).containsExactly("begin", "commit failed", "begin", "commit");
    }

    @Test
    void afterThreeAbortedAttemptsItGivesUpAsBusy() {
        RuntimeException third = deadlock();
        Attempts attempts = new Attempts(deadlock(), deadlock(), third);

        assertThatThrownBy(() -> retrying.execute(status -> attempts.next()))
                .isInstanceOf(AccountBusyException.class)
                .hasCause(third);
        assertThat(attempts.made).isEqualTo(RetryingTransactions.MAX_ATTEMPTS).isEqualTo(3);
        assertThat(pauses).hasSize(2);
    }

    @Test
    void aLockTimeoutIsNeverRetriedAndBecomesBusy() {
        RuntimeException timeout = lockTimeout();
        Attempts attempts = new Attempts(timeout);

        assertThatThrownBy(() -> retrying.execute(status -> attempts.next()))
                .isInstanceOf(AccountBusyException.class)
                .hasCause(timeout);
        assertThat(attempts.made).isOne();
        assertThat(pauses).isEmpty();
    }

    @Test
    void aBusyAccountFromThePostingIsPassedOnUnchanged() {
        AccountBusyException busy = AccountBusyException.lockTimedOut(lockTimeout());
        Attempts attempts = new Attempts(busy);

        assertThatThrownBy(() -> retrying.execute(status -> attempts.next())).isSameAs(busy);
        assertThat(attempts.made).isOne();
    }

    @Test
    void businessErrorsAndOtherDatabaseErrorsAreNeverRetried() {
        InsufficientFundsException insufficient = new InsufficientFundsException(new AccountId(UUID.randomUUID()));
        DataIntegrityViolationException duplicateKey = new DataIntegrityViolationException(
                "duplicate key", new SQLException("duplicate key value violates unique constraint", "23505"));

        for (RuntimeException failure : List.of(insufficient, duplicateKey)) {
            Attempts attempts = new Attempts(failure);
            assertThatThrownBy(() -> retrying.execute(status -> attempts.next()))
                    .isSameAs(failure);
            assertThat(attempts.made).isOne();
        }
        assertThat(pauses).isEmpty();
    }

    @Test
    void workThatJoinedTheCallersTransactionIsNotRetried() {
        // The caller's transaction is already aborted. Only the caller can start again, so the error goes back as it
        // is.
        transactionManager.joinsAnExistingTransaction = true;
        RuntimeException deadlock = deadlock();
        Attempts attempts = new Attempts(deadlock);

        assertThatThrownBy(() -> retrying.execute(status -> attempts.next())).isSameAs(deadlock);
        assertThat(attempts.made).isOne();
    }

    @Test
    void anInterruptedPauseStopsRetryingAndKeepsTheInterrupt() {
        RetryingTransactions interrupted = new RetryingTransactions(transactionManager, pause -> {
            throw new InterruptedException();
        });
        RuntimeException deadlock = deadlock();
        Attempts attempts = new Attempts(deadlock);

        assertThatThrownBy(() -> interrupted.execute(status -> attempts.next())).isSameAs(deadlock);
        assertThat(attempts.made).isOne();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void eachPauseIsRandomUpToALimitThatDoublesWithEachRetry() {
        Random random = new Random(42);
        for (int i = 0; i < 1_000; i++) {
            assertThat(RetryingTransactions.pauseBeforeRetry(1, random))
                    .isBetween(Duration.ZERO, Duration.ofMillis(25));
            assertThat(RetryingTransactions.pauseBeforeRetry(2, random))
                    .isBetween(Duration.ZERO, Duration.ofMillis(50));
        }
    }

    // --- helpers ---

    /** Database errors as the Postgres driver reports them, wrapped the way Spring wraps them. */
    private static RuntimeException deadlock() {
        return new PessimisticLockingFailureException("deadlock", new SQLException("deadlock detected", "40P01"));
    }

    private static RuntimeException serializationFailure() {
        return new PessimisticLockingFailureException(
                "serialization", new SQLException("could not serialize access", "40001"));
    }

    private static RuntimeException lockTimeout() {
        return new CannotAcquireLockException(
                "lock timeout", new SQLException("canceling statement due to lock timeout", "55P03"));
    }

    /** Work that fails with each of these in turn, then succeeds with "moved". */
    private static final class Attempts {
        final Deque<RuntimeException> failures;
        int made;

        Attempts(RuntimeException... failures) {
            this.failures = new ArrayDeque<>(List.of(failures));
        }

        String next() {
            made++;
            RuntimeException failure = failures.poll();
            if (failure != null) {
                throw failure;
            }
            return "moved";
        }
    }

    /** Records what happens to each transaction, and can fail a commit. */
    private static final class FakeTransactionManager implements PlatformTransactionManager {
        final List<String> log = new ArrayList<>();
        final Deque<RuntimeException> failCommits = new ArrayDeque<>();
        boolean joinsAnExistingTransaction;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            log.add("begin");
            return new SimpleTransactionStatus(!joinsAnExistingTransaction);
        }

        @Override
        public void commit(TransactionStatus status) {
            RuntimeException failure = failCommits.poll();
            if (failure != null) {
                log.add("commit failed");
                throw failure;
            }
            log.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            log.add("rollback");
        }
    }
}
