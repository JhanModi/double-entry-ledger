package io.github.jhanmodi.ledger.idempotency;

import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.ledger.SqlState;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims idempotency keys (ADR-0023, building on ADR-0007). A claim is a row in {@code idempotency_keys}, inserted as
 * the first statement of the operation's own transaction, so it commits with the money movement or rolls back with it.
 * Nothing about the request's result is stored here: a replay is rebuilt from the operation's own record.
 */
@Component
public class IdempotencyKeys {

    /**
     * How many times to try claiming. A second try is only needed if the cleanup deleted an expired claim between the
     * insert and the select (see {@link #claim}).
     */
    private static final int MAX_CLAIM_ATTEMPTS = 2;

    private final JdbcClient jdbc;
    private final Duration lockTimeout;
    private final Duration retention;

    public IdempotencyKeys(
            JdbcClient jdbc,
            @Value("${ledger.lock-timeout}") Duration lockTimeout,
            @Value("${ledger.idempotency.retention}") Duration retention) {
        // Zero would mean "wait forever" to Postgres (ADR-0022).
        if (lockTimeout.toMillis() <= 0) {
            throw new IllegalArgumentException("ledger.lock-timeout must be at least 1ms, got " + lockTimeout);
        }
        if (retention.toMillis() <= 0) {
            throw new IllegalArgumentException("ledger.idempotency.retention must be at least 1ms, got " + retention);
        }
        this.jdbc = jdbc;
        this.lockTimeout = lockTimeout;
        this.retention = retention;
    }

    /**
     * Claims the key for this request, as part of the caller's transaction. Call it first, before anything else the
     * request does.
     *
     * <ol>
     *   <li>Limit lock waits, as a posting does: the insert below may have to wait for another transaction.
     *   <li>Insert the claim, unless this client already has one for this key ({@code ON CONFLICT DO NOTHING}). If
     *       another transaction has inserted the same key and not committed yet, the insert waits to see how it ends:
     *       if it commits, nothing is inserted; if it rolls back, the claim is inserted.
     *   <li>If nothing was inserted, read the existing claim and compare it with this request.
     * </ol>
     *
     * <p>The read is a separate statement on purpose. At READ COMMITTED each statement sees the database as it was when
     * that statement started, so a select inside the insert's statement couldn't see a claim committed while the insert
     * waited. The next statement can.
     *
     * @return {@link Claim#NEW} if the key was free and is now this request's; {@link Claim#REPEAT} if the same request
     *     already used it and was committed
     * @throws IdempotencyKeyReusedException the key was used for a different operation, or with a different fingerprint
     * @throws RequestInProgressException another request still held the key when the lock timeout ran out
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(
            ClientId client, IdempotencyKey key, IdempotentOperation operation, RequestFingerprint fingerprint) {
        limitLockWaits();
        for (int attempt = 1; attempt <= MAX_CLAIM_ATTEMPTS; attempt++) {
            if (insert(client, key, operation, fingerprint)) {
                return Claim.NEW;
            }
            Optional<StoredClaim> existing = find(client, key);
            if (existing.isPresent()) {
                if (!existing.get().matches(operation, fingerprint)) {
                    throw new IdempotencyKeyReusedException();
                }
                return Claim.REPEAT;
            }
            // There for the insert, gone for the select: the cleanup deleted it in between, because it had expired.
            // The key is free again, so claim it.
        }
        throw new IllegalStateException("the claim for this key disappeared on every attempt");
    }

    /**
     * Sets Postgres's {@code lock_timeout} for the rest of this transaction: the same setting, and the same SQL, as
     * {@code PostingService} (ADR-0022). Set here too, because the claim is the transaction's first statement and may
     * wait for another request with the same key.
     */
    private void limitLockWaits() {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", lockTimeout.toMillis() + "ms")
                .query(String.class)
                .single();
    }

    /** Whether the claim was inserted. False if this client already has a committed claim for the key. */
    private boolean insert(
            ClientId client, IdempotencyKey key, IdempotentOperation operation, RequestFingerprint fingerprint) {
        try {
            return jdbc.sql("""
                            INSERT INTO idempotency_keys (client_id, idempotency_key, operation, request_hash, expires_at)
                            VALUES (:clientId, :key, :operation, :requestHash,
                                    now() + :retentionMillis * interval '1 millisecond')
                            ON CONFLICT (client_id, idempotency_key) DO NOTHING
                            """)
                            .param("clientId", client.value())
                            .param("key", key.value())
                            .param("operation", operation.name())
                            .param("requestHash", fingerprint.bytes())
                            .param("retentionMillis", retention.toMillis())
                            .update()
                    == 1;
        } catch (DataAccessException e) {
            if (SqlState.is(e, SqlState.LOCK_NOT_AVAILABLE)) {
                throw new RequestInProgressException();
            }
            throw e;
        }
    }

    private Optional<StoredClaim> find(ClientId client, IdempotencyKey key) {
        return jdbc.sql("""
                        SELECT operation, request_hash FROM idempotency_keys
                        WHERE client_id = :clientId AND idempotency_key = :key
                        """)
                .param("clientId", client.value())
                .param("key", key.value())
                .query((rs, rowNum) -> new StoredClaim(
                        IdempotentOperation.valueOf(rs.getString("operation")),
                        RequestFingerprint.fromBytes(rs.getBytes("request_hash"))))
                .optional();
    }

    /** What a committed claim says about the request that made it. */
    private record StoredClaim(IdempotentOperation operation, RequestFingerprint fingerprint) {

        boolean matches(IdempotentOperation otherOperation, RequestFingerprint otherFingerprint) {
            return operation == otherOperation && fingerprint.equals(otherFingerprint);
        }
    }
}
