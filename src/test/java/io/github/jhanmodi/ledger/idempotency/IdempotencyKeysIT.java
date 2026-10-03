package io.github.jhanmodi.ledger.idempotency;

import static io.github.jhanmodi.ledger.DatabaseLocks.awaitASessionWaitingForALock;
import static io.github.jhanmodi.ledger.idempotency.IdempotentOperation.FUNDING;
import static io.github.jhanmodi.ledger.idempotency.IdempotentOperation.TRANSFER;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.HeldTransaction;
import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claiming idempotency keys against a real database (ADR-0023), as the application's restricted login: a free key is
 * claimed, the same request again is a repeat, a different request is refused, and a claim rolled back with its
 * transaction frees the key.
 *
 * <p>The race tests hold another transaction's uncommitted claim open (as the owner, with raw SQL), start a claim for the
 * same key, and wait until Postgres reports it blocked before letting the first commit, roll back, or outlast the lock
 * timeout. No sleeping.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyKeysIT {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

    @Autowired
    IdempotencyKeys keys;

    @Autowired
    ClientService clientService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerDatabase owner;

    ClientId alice;
    ExecutorService otherRequest;

    @BeforeEach
    void setUp() {
        alice = clientService.createClient("alice");
        otherRequest = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        otherRequest.shutdownNow();
    }

    // --- One request at a time ---

    @Test
    void aFreeKeyIsClaimedForTheOperationAndFingerprint() {
        IdempotencyKey key = key();
        RequestFingerprint fingerprint = fingerprint("first");

        assertThat(claim(alice, key, TRANSFER, fingerprint)).isEqualTo(Claim.NEW);

        assertThat(jdbc.sql("""
                        SELECT operation FROM idempotency_keys WHERE client_id = :clientId AND idempotency_key = :key
                        """)
                        .param("clientId", alice.value())
                        .param("key", key.value())
                        .query(String.class)
                        .single())
                .isEqualTo("TRANSFER");
        assertThat(storedFingerprint(alice, key)).isEqualTo(fingerprint);
    }

    @Test
    void theSameRequestAgainIsARepeat() {
        IdempotencyKey key = key();
        claim(alice, key, TRANSFER, fingerprint("first"));

        assertThat(claim(alice, key, TRANSFER, fingerprint("first"))).isEqualTo(Claim.REPEAT);
        assertThat(claim(alice, key, TRANSFER, fingerprint("first"))).isEqualTo(Claim.REPEAT);
        assertThat(claimsFor(alice, key)).isOne();
    }

    @Test
    void aDifferentRequestWithTheSameKeyIsRefused() {
        IdempotencyKey key = key();
        claim(alice, key, TRANSFER, fingerprint("first"));

        assertThatThrownBy(() -> claim(alice, key, TRANSFER, fingerprint("second")))
                .as("same operation, different values")
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> claim(alice, key, FUNDING, fingerprint("first")))
                .as("a different operation, even with an identical fingerprint")
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(storedFingerprint(alice, key)).isEqualTo(fingerprint("first"));
    }

    @Test
    void eachClientHasItsOwnKeys() {
        IdempotencyKey key = key();
        ClientId bob = clientService.createClient("bob");
        claim(alice, key, TRANSFER, fingerprint("alice's"));

        // Bob's claim is new: he never sees, or collides with, Alice's.
        assertThat(claim(bob, key, TRANSFER, fingerprint("bob's"))).isEqualTo(Claim.NEW);
        assertThat(storedFingerprint(alice, key)).isEqualTo(fingerprint("alice's"));
        assertThat(storedFingerprint(bob, key)).isEqualTo(fingerprint("bob's"));

        // Each one's retry is compared with their own claim only.
        assertThat(claim(alice, key, TRANSFER, fingerprint("alice's"))).isEqualTo(Claim.REPEAT);
        assertThat(claim(bob, key, TRANSFER, fingerprint("bob's"))).isEqualTo(Claim.REPEAT);
        assertThatThrownBy(() -> claim(alice, key, TRANSFER, fingerprint("bob's")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void aClaimRolledBackWithItsTransactionFreesTheKey() {
        IdempotencyKey key = key();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(keys.claim(alice, key, TRANSFER, fingerprint("failed"))).isEqualTo(Claim.NEW);
            status.setRollbackOnly(); // as when the operation fails, e.g. with insufficient funds
        });

        assertThat(claimsFor(alice, key)).isZero();
        assertThat(claim(alice, key, TRANSFER, fingerprint("a different request")))
                .as("the failed request never took the key")
                .isEqualTo(Claim.NEW);
    }

    @Test
    void aClaimIsOnlyEverMadeInsideTheOperationsTransaction() {
        // On its own, the claim would commit by itself, and the key would be taken even if the operation then failed.
        assertThatThrownBy(() -> keys.claim(alice, key(), TRANSFER, fingerprint("x")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aClaimExpiresAfterTheRetentionPeriod() {
        IdempotencyKey key = key();
        claim(alice, key, TRANSFER, fingerprint("x"));

        Duration kept = jdbc.sql("""
                        SELECT created_at, expires_at FROM idempotency_keys
                        WHERE client_id = :clientId AND idempotency_key = :key
                        """)
                .param("clientId", alice.value())
                .param("key", key.value())
                .query((rs, rowNum) -> Duration.between(
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .single();
        assertThat(kept).isEqualTo(Duration.ofHours(24));
    }

    // --- Two requests with the same key at once ---

    @Test
    void aClaimWaitsForAnUncommittedOneAndIsARepeatOnceItCommits() throws Exception {
        IdempotencyKey key = key();
        try (HeldTransaction first = HeldTransaction.start(owner, sql -> insertClaim(sql, key, fingerprint("same")))) {
            Future<Claim> second = otherRequest.submit(() -> claim(alice, key, TRANSFER, fingerprint("same")));
            awaitASessionWaitingForALock(owner);

            first.commit();

            assertThat(second.get(WAIT_LIMIT.toSeconds(), SECONDS)).isEqualTo(Claim.REPEAT);
        }
    }

    @Test
    void aClaimWaitsForAnUncommittedOneAndTakesTheKeyIfItRollsBack() throws Exception {
        IdempotencyKey key = key();
        try (HeldTransaction first =
                HeldTransaction.start(owner, sql -> insertClaim(sql, key, fingerprint("failed")))) {
            Future<Claim> second = otherRequest.submit(() -> claim(alice, key, TRANSFER, fingerprint("same")));
            awaitASessionWaitingForALock(owner);

            first.rollback();

            assertThat(second.get(WAIT_LIMIT.toSeconds(), SECONDS)).isEqualTo(Claim.NEW);
        }
        assertThat(storedFingerprint(alice, key)).isEqualTo(fingerprint("same"));
    }

    @Test
    void aClaimThatWaitsLongerThanTheLockTimeoutIsRefusedAsInProgress() throws Exception {
        IdempotencyKey key = key();
        try (HeldTransaction first = HeldTransaction.start(owner, sql -> insertClaim(sql, key, fingerprint("same")))) {
            Future<Claim> second = otherRequest.submit(() -> claim(alice, key, TRANSFER, fingerprint("same")));

            // The first never finishes while the second waits, so the second gives up after the 2-second lock timeout.
            assertThatThrownBy(() -> second.get(WAIT_LIMIT.toSeconds(), SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(RequestInProgressException.class)
                    .hasNoCause();
        }
    }

    @Test
    void anUncommittedClaimDoesNotHoldUpOtherKeys() throws Exception {
        try (HeldTransaction first = HeldTransaction.start(owner, sql -> insertClaim(sql, key(), fingerprint("x")))) {
            // Only the same client and key wait; a different key goes straight through while the first is held.
            assertThat(otherRequest
                            .submit(() -> claim(alice, key(), TRANSFER, fingerprint("x")))
                            .get(WAIT_LIMIT.toSeconds(), SECONDS))
                    .isEqualTo(Claim.NEW);
        }
    }

    // --- helpers ---

    /** Claims the key in a transaction of its own, which commits, as a successful operation's would. */
    private Claim claim(
            ClientId client, IdempotencyKey key, IdempotentOperation operation, RequestFingerprint fingerprint) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> keys.claim(client, key, operation, fingerprint));
    }

    /** Another request's claim, made as the owner inside the held transaction, for Alice's transfer. */
    private void insertClaim(JdbcClient sql, IdempotencyKey key, RequestFingerprint fingerprint) {
        sql.sql("""
                        INSERT INTO idempotency_keys (client_id, idempotency_key, operation, request_hash, expires_at)
                        VALUES (:clientId, :key, 'TRANSFER', :requestHash, now() + interval '1 day')
                        """)
                .param("clientId", alice.value())
                .param("key", key.value())
                .param("requestHash", fingerprint.bytes())
                .update();
    }

    private RequestFingerprint storedFingerprint(ClientId client, IdempotencyKey key) {
        return jdbc.sql("""
                        SELECT request_hash FROM idempotency_keys
                        WHERE client_id = :clientId AND idempotency_key = :key
                        """)
                .param("clientId", client.value())
                .param("key", key.value())
                .query((rs, rowNum) -> RequestFingerprint.fromBytes(rs.getBytes("request_hash")))
                .single();
    }

    private long claimsFor(ClientId client, IdempotencyKey key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE client_id = :clientId AND idempotency_key = :key")
                .param("clientId", client.value())
                .param("key", key.value())
                .query(Long.class)
                .single();
    }

    private static RequestFingerprint fingerprint(String value) {
        return RequestFingerprint.of(TRANSFER).field("value", value).build();
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey("test-" + UUID.randomUUID());
    }
}
