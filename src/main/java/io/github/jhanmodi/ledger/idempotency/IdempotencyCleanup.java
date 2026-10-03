package io.github.jhanmodi.ledger.idempotency;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes expired idempotency-key claims (ADR-0023, D5). A claim is kept for at least the retention period, 24 hours by
 * default; after that this job may delete it, and a retry with that key gets 409 {@code duplicate-request} instead of
 * a replay. Claims aren't financial records: the transfer or funding row keeps its key for good, so deleting a claim
 * can cost a client its replay, never a second debit.
 *
 * <p>Only the web server runs it, not the command-line mode. Several servers running it at once is harmless: a claim
 * one of them deleted simply isn't there for the others.
 */
@Component
@ConditionalOnWebApplication
public class IdempotencyCleanup {

    /** Claims deleted per statement, so that each delete is a short transaction holding few row locks. */
    static final int BATCH_SIZE = 1_000;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

    private final JdbcClient jdbc;
    private final int batchSize;

    @Autowired
    public IdempotencyCleanup(JdbcClient jdbc, @Value("${ledger.idempotency.cleanup-interval}") Duration interval) {
        this(jdbc, interval, BATCH_SIZE);
    }

    /** For tests, which use a small batch to show that a cleanup takes as many batches as it needs. */
    IdempotencyCleanup(JdbcClient jdbc, Duration interval, int batchSize) {
        // Zero would mean "run again at once, forever".
        if (interval.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    "ledger.idempotency.cleanup-interval must be at least 1ms, got " + interval);
        }
        this.jdbc = jdbc;
        this.batchSize = batchSize;
    }

    /** Runs {@link #deleteExpired} on a schedule: first one interval after startup, then one interval after each run. */
    @Scheduled(
            initialDelayString = "${ledger.idempotency.cleanup-interval}",
            fixedDelayString = "${ledger.idempotency.cleanup-interval}")
    void deleteExpiredOnSchedule() {
        deleteExpired();
    }

    /**
     * Deletes every claim whose {@code expires_at} has passed, by database time, a batch at a time.
     *
     * <p>Outside a transaction, each statement commits on its own, so no transaction ever holds more than one batch's
     * row locks. A claim being re-taken for its key at the same moment is safe either way: the new claim's insert waits
     * for this delete, then goes ahead.
     *
     * @return how many claims were deleted
     */
    public int deleteExpired() {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.sql("""
                            DELETE FROM idempotency_keys
                            WHERE (client_id, idempotency_key) IN (
                                SELECT client_id, idempotency_key FROM idempotency_keys
                                WHERE expires_at < now()
                                ORDER BY expires_at
                                LIMIT :batchSize)
                            """).param("batchSize", batchSize).update();
            total += deleted;
        } while (deleted == batchSize);
        if (total > 0) {
            log.info("Deleted {} expired idempotency keys", total);
        }
        return total;
    }
}
