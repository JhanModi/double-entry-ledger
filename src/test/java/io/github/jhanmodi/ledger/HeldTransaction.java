package io.github.jhanmodi.ledger;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A transaction on the owner's connection, held open on its own thread until the test commits or rolls it back.
 *
 * <p>Used to create one exact interleaving: take a lock here, start the code under test on another thread, wait until
 * Postgres reports that code blocked ({@link DatabaseLocks#awaitASessionWaitingForALock}), then release the lock, or
 * hand the transaction its next step with {@link #then}. No sleeping and no guessed timings.
 */
public final class HeldTransaction implements AutoCloseable {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

    /** What the transaction's thread does next: run some work, or end the transaction. */
    private sealed interface Step permits Work, End {}

    private record Work(Consumer<JdbcClient> work, CompletableFuture<Void> done) implements Step {}

    private record End(boolean commit) implements Step {}

    private final ExecutorService thread = Executors.newSingleThreadExecutor();
    private final BlockingQueue<Step> steps = new LinkedBlockingQueue<>();
    private final Future<?> finished;
    private boolean ended;

    private HeldTransaction(OwnerDatabase owner) {
        finished = thread.submit(() -> owner.transactions().executeWithoutResult(status -> {
            while (true) {
                switch (nextStep()) {
                    case Work(Consumer<JdbcClient> work, CompletableFuture<Void> done) -> {
                        try {
                            work.accept(owner.jdbc());
                        } catch (RuntimeException e) {
                            done.completeExceptionally(e);
                            throw e;
                        }
                        done.complete(null);
                    }
                    case End(boolean commit) -> {
                        if (!commit) {
                            status.setRollbackOnly();
                        }
                        return;
                    }
                }
            }
        }));
    }

    /** Runs the work in a new transaction as the owner, and returns once it has run, with the transaction still open. */
    public static HeldTransaction start(OwnerDatabase owner, Consumer<JdbcClient> work) {
        HeldTransaction held = new HeldTransaction(owner);
        try {
            await(held.then(work));
        } catch (RuntimeException | AssertionError e) {
            held.close();
            throw e;
        }
        return held;
    }

    /**
     * Runs more work in the same transaction, and returns at once without waiting for it. The future completes when the
     * work has run. For work that blocks, such as asking for a lock the code under test holds.
     */
    public Future<Void> then(Consumer<JdbcClient> work) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        steps.add(new Work(work, done));
        return done;
    }

    /** Commits, which releases its locks, and waits until the commit has happened. */
    public void commit() {
        end(true);
        await(finished);
    }

    /** Rolls back, which releases its locks, and waits until the rollback has happened. */
    public void rollback() {
        end(false);
        await(finished);
    }

    /** Rolls back if the test hasn't ended the transaction already. Never hides the test's own failure. */
    @Override
    public void close() {
        end(false);
        try {
            finished.get(WAIT_LIMIT.toSeconds(), SECONDS);
        } catch (Exception e) {
            // The transaction's own failure has already been reported to the test, through the step that failed.
        } finally {
            thread.shutdownNow();
        }
    }

    private void end(boolean commit) {
        if (!ended) {
            ended = true;
            steps.add(new End(commit));
        }
    }

    private Step nextStep() {
        try {
            Step step = steps.poll(WAIT_LIMIT.toSeconds(), SECONDS);
            if (step == null) {
                throw new IllegalStateException("the test gave this transaction nothing to do for " + WAIT_LIMIT);
            }
            return step;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Waits for a step or for the whole transaction, and reports its failure as the test's own. */
    public static void await(Future<?> future) {
        try {
            future.get(WAIT_LIMIT.toSeconds(), SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new AssertionError("the held transaction failed", e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("the held transaction didn't finish within " + WAIT_LIMIT, e);
        }
    }
}
