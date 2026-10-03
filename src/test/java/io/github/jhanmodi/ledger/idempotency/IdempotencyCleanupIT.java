package io.github.jhanmodi.ledger.idempotency;

import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountService;
import io.github.jhanmodi.ledger.ledger.LedgerFixtures;
import io.github.jhanmodi.ledger.ledger.LedgerQueries;
import io.github.jhanmodi.ledger.ledger.PostingService;
import io.github.jhanmodi.ledger.money.Money;
import io.github.jhanmodi.ledger.transfers.DuplicateRequestException;
import io.github.jhanmodi.ledger.transfers.Transfer;
import io.github.jhanmodi.ledger.transfers.TransferCommand;
import io.github.jhanmodi.ledger.transfers.TransferService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The cleanup of expired idempotency-key claims (ADR-0023, D5): it deletes expired claims and only those, as the
 * application's restricted login, a batch at a time; the web server schedules it; and a retry after its claim was
 * deleted still can't move money twice.
 *
 * <p>Expired claims are made as the owner, with timestamps in the past. A claim's timestamps can't be changed (a trigger
 * rejects any UPDATE), so an existing claim is "aged" by deleting it and inserting it again.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyCleanupIT {

    @Autowired
    IdempotencyCleanup cleanup;

    @Autowired
    List<ScheduledTaskHolder> schedulers;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerDatabase owner;

    @Autowired
    ClientService clientService;

    @Autowired
    AccountService accountService;

    @Autowired
    PostingService postingService;

    @Autowired
    LedgerQueries queries;

    @Autowired
    TransferService transfers;

    @Test
    void deletesEveryExpiredClaimAndNothingElseABatchAtATime() {
        ClientId client = clientService.createClient("cleanup");
        List<String> expired = new ArrayList<>();
        List<String> live = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expired.add(insertClaim(client, "2 days", "1 second"));
        }
        live.add(insertClaim(client, "1 day", "-1 hour")); // created a day ago, expires in an hour
        live.add(insertClaim(client, "0 seconds", "-1 day")); // just claimed

        // As the application's own login, two claims per statement: five expired claims take three batches. (Only the
        // claims' state is checked, not the count returned: the scheduled cleanup may also run during a long test run.)
        new IdempotencyCleanup(jdbc, Duration.ofMinutes(10), 2).deleteExpired();

        assertThat(keysOf(client)).doesNotContainAnyElementsOf(expired).containsExactlyInAnyOrderElementsOf(live);
    }

    @Test
    void theWebServerRunsTheCleanupOnTheConfiguredSchedule() {
        List<ScheduledTask> cleanups = schedulers.stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .filter(task -> task.toString().contains("IdempotencyCleanup.deleteExpiredOnSchedule"))
                .toList();

        assertThat(cleanups).hasSize(1);
        assertThat(cleanups.getFirst().getTask()).isInstanceOfSatisfying(FixedDelayTask.class, task -> {
            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(10));
            assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(10));
        });
    }

    @Test
    void aRetryAfterTheCleanupDeletedItsClaimStillCantMoveMoneyTwice() {
        LedgerFixtures ledger = new LedgerFixtures(accountService, postingService, clientService);
        ClientId client = ledger.client();
        AccountId main = ledger.customer(USD);
        AccountId savings = ledger.customer(USD);
        ledger.fund(ledger.bank(USD), main, usd(1000));
        IdempotencyKey key = new IdempotencyKey("test-" + UUID.randomUUID());
        TransferCommand command = new TransferCommand(main, savings, usd(1000), null, key);
        Transfer original = transfers.transfer(command, ledger.caller(client)).value();

        ageClaim(client, key);
        cleanup.deleteExpired();
        assertThat(keysOf(client)).doesNotContain(key.value());

        // No replay now, but the transfer row still has the key: the retry is refused, and nothing moves.
        assertThatThrownBy(() -> transfers.transfer(command, ledger.caller(client)))
                .isInstanceOfSatisfying(
                        DuplicateRequestException.class,
                        e -> assertThat(e.originalId()).isEqualTo(original.id().value()));
        assertThat(queries.balance(main).posted()).isEqualTo(usd(0));
        assertThat(queries.balance(savings).posted()).isEqualTo(usd(1000));
    }

    // --- helpers ---

    /**
     * A claim made {@code age} ago that expires {@code expiredFor} before now (a negative interval means it's still
     * live). Inserted as the owner, which may set the timestamps.
     */
    private String insertClaim(ClientId client, String age, String expiredFor) {
        String key = "test-" + UUID.randomUUID();
        owner.jdbc()
                .sql("""
                        INSERT INTO idempotency_keys
                            (client_id, idempotency_key, operation, request_hash, created_at, expires_at)
                        VALUES (:clientId, :key, 'TRANSFER', decode(repeat('ab', 32), 'hex'),
                                now() - CAST(:age AS interval), now() - CAST(:expiredFor AS interval))
                        """)
                .param("clientId", client.value())
                .param("key", key)
                .param("age", age)
                .param("expiredFor", expiredFor)
                .update();
        return key;
    }

    /** Makes an existing claim look 25 hours old, and expired an hour ago, keeping everything else about it. */
    private void ageClaim(ClientId client, IdempotencyKey key) {
        owner.transactions().executeWithoutResult(status -> {
            record Stored(String operation, byte[] requestHash) {}
            Stored stored = owner.jdbc()
                    .sql("""
                            DELETE FROM idempotency_keys WHERE client_id = :clientId AND idempotency_key = :key
                            RETURNING operation, request_hash
                            """)
                    .param("clientId", client.value())
                    .param("key", key.value())
                    .query((rs, rowNum) -> new Stored(rs.getString("operation"), rs.getBytes("request_hash")))
                    .single();
            owner.jdbc()
                    .sql("""
                            INSERT INTO idempotency_keys
                                (client_id, idempotency_key, operation, request_hash, created_at, expires_at)
                            VALUES (:clientId, :key, :operation, :requestHash,
                                    now() - interval '25 hours', now() - interval '1 hour')
                            """)
                    .param("clientId", client.value())
                    .param("key", key.value())
                    .param("operation", stored.operation())
                    .param("requestHash", stored.requestHash())
                    .update();
        });
    }

    private List<String> keysOf(ClientId client) {
        return jdbc.sql("SELECT idempotency_key FROM idempotency_keys WHERE client_id = :clientId")
                .param("clientId", client.value())
                .query(String.class)
                .list();
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
