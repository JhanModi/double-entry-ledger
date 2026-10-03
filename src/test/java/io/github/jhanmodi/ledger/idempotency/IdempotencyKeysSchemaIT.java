package io.github.jhanmodi.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves each database guard on {@code idempotency_keys} (V6, ADR-0023) works on its own, by going around the Java code
 * with raw SQL as the owner, as LedgerSchemaIT and MoneyMovementSchemaIT do. Every statement runs in a rolled-back
 * transaction, so nothing here is ever committed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyKeysSchemaIT {

    @Autowired
    OwnerDatabase owner;

    @Autowired
    ClientService clientService;

    JdbcClient jdbc;
    TransactionTemplate tx;
    ClientId alice;

    @BeforeEach
    void setUp() {
        jdbc = owner.jdbc();
        tx = owner.transactions();
        alice = clientService.createClient("alice");
    }

    @Test
    void aValidClaimIsAccepted() {
        // The baseline: without it, the rejections below could be failing for some other reason.
        assertAccepted(() -> insert(claim()));
    }

    @Test
    void aClientHasAtMostOneClaimPerKey() {
        assertRejected(
                () -> {
                    Map<String, Object> first = claim();
                    insert(first);
                    Map<String, Object> second = claim();
                    second.put("key", first.get("key"));
                    second.put("operation", "FUNDING");
                    insert(second);
                },
                "idempotency_keys_pkey");
    }

    @Test
    void theClientMustExist() {
        Map<String, Object> row = claim();
        row.put("clientId", UUID.randomUUID());

        assertRejected(() -> insert(row), "idempotency_keys_client_id_fkey");
    }

    @Test
    void aKeyIs1To255SafeCharacters() {
        for (String valid : new String[] {"a", "order-123:attempt_2.v1", "k".repeat(255)}) {
            Map<String, Object> row = claim();
            row.put("key", valid);

            assertAccepted(() -> insert(row));
        }
        for (String invalid : new String[] {"", "has space", "café", "line\nbreak", "k".repeat(256)}) {
            Map<String, Object> row = claim();
            row.put("key", invalid);

            assertRejected(() -> insert(row), "idempotency_keys_key_format");
        }
    }

    @Test
    void onlyKnownOperationsTakeKeys() {
        Map<String, Object> row = claim();
        row.put("operation", "PAYMENT");

        assertRejected(() -> insert(row), "idempotency_keys_operation_known");
    }

    @Test
    void theRequestHashIsExactly32Bytes() {
        for (int bytes : new int[] {0, 31, 33}) {
            Map<String, Object> row = claim();
            row.put("hashBytes", bytes);

            assertRejected(() -> insert(row), "idempotency_keys_request_hash_is_sha256");
        }
    }

    @Test
    void aClaimExpiresAfterItWasCreated() {
        Map<String, Object> row = claim();
        row.put("expiresIn", "0 seconds");

        assertRejected(() -> insert(row), "idempotency_keys_expire_after_creation");
    }

    @Test
    void aClaimNeverChangesButCanBeDeleted() {
        assertRejected(
                () -> {
                    Map<String, Object> row = claim();
                    insert(row);
                    jdbc.sql("UPDATE idempotency_keys SET request_hash = request_hash WHERE idempotency_key = :key")
                            .param("key", row.get("key"))
                            .update();
                },
                "a claimed idempotency key never changes");
        assertAccepted(() -> {
            Map<String, Object> row = claim();
            insert(row);
            jdbc.sql("DELETE FROM idempotency_keys WHERE idempotency_key = :key")
                    .param("key", row.get("key"))
                    .update();
        });
    }

    // --- helpers ---

    /** A claim every guard accepts: Alice's transfer key, with a 32-byte hash, expiring in a day. */
    private Map<String, Object> claim() {
        Map<String, Object> row = new HashMap<>();
        row.put("clientId", alice.value());
        row.put("key", "key-" + UUID.randomUUID());
        row.put("operation", "TRANSFER");
        row.put("hashBytes", 32);
        row.put("expiresIn", "1 day");
        return row;
    }

    private void insert(Map<String, Object> row) {
        jdbc.sql("""
                        INSERT INTO idempotency_keys (client_id, idempotency_key, operation, request_hash, expires_at)
                        VALUES (:clientId, :key, :operation, decode(repeat('ab', :hashBytes), 'hex'),
                                now() + CAST(:expiresIn AS interval))
                        """).params(row).update();
    }

    private void assertAccepted(Runnable work) {
        assertThatCode(() -> rollbackOnly(work)).doesNotThrowAnyException();
    }

    /** The work fails, and the database's error names the expected constraint or reason. */
    private void assertRejected(Runnable work, String expected) {
        assertThatThrownBy(() -> rollbackOnly(work)).rootCause().hasMessageContaining(expected);
    }

    /** Runs the work, then rolls back whatever happened. */
    private void rollbackOnly(Runnable work) {
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            work.run();
        });
    }
}
