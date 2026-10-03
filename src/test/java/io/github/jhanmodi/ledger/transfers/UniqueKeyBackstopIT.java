package io.github.jhanmodi.ledger.transfers;

import static io.github.jhanmodi.ledger.DatabaseLocks.awaitASessionWaitingForALock;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.Caller;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.clients.Scope;
import io.github.jhanmodi.ledger.idempotency.Claim;
import io.github.jhanmodi.ledger.idempotency.IdempotencyKey;
import io.github.jhanmodi.ledger.idempotency.IdempotencyKeys;
import io.github.jhanmodi.ledger.idempotency.IdempotentResult;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountService;
import io.github.jhanmodi.ledger.ledger.InvariantChecker;
import io.github.jhanmodi.ledger.ledger.LedgerFixtures;
import io.github.jhanmodi.ledger.ledger.LedgerQueries;
import io.github.jhanmodi.ledger.ledger.PostingService;
import io.github.jhanmodi.ledger.money.Money;
import java.time.Duration;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The permanent backstop on its own (ADR-0023): with the claim layer switched off, the {@code UNIQUE (client_id,
 * idempotency_key)} on transfers and fundings still lets a key move money only once. In the rest of the suite the claim
 * stops a duplicate first, so this backstop never gets the chance to act there; a bug in the claim layer would be hidden
 * if the backstop were only ever tested behind it.
 *
 * <p>The claim layer is replaced by a mock that lets every request through as new. That needs its own Spring context
 * (and so its own database container), which is why these tests live in a class of their own.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UniqueKeyBackstopIT {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

    @MockitoBean
    IdempotencyKeys switchedOffClaims;

    @Autowired
    TransferService transfers;

    @Autowired
    FundingService fundings;

    @Autowired
    AccountService accountService;

    @Autowired
    PostingService postingService;

    @Autowired
    ClientService clientService;

    @Autowired
    LedgerQueries queries;

    @Autowired
    InvariantChecker invariantChecker;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerDatabase owner;

    LedgerFixtures ledger;
    ClientId client;
    AccountId main;
    AccountId savings;
    ExecutorService threads;

    @BeforeEach
    void setUp() {
        when(switchedOffClaims.claim(any(), any(), any(), any())).thenReturn(Claim.NEW);
        ledger = new LedgerFixtures(accountService, postingService, clientService);
        client = ledger.client();
        main = ledger.customer(USD);
        savings = ledger.customer(USD);
        ledger.fund(ledger.bank(USD), main, usd(1000));
        threads = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        threads.shutdownNow();
    }

    @Test
    void aSecondTransferThatSlipsPastTheClaimIsRefusedBeforeMovingMoney() {
        IdempotencyKey key = key();
        Transfer original = transfers
                .transfer(new TransferCommand(main, savings, usd(100), null, key), ledger.caller(client))
                .value();

        // The transfer row already has the key, and the service looks for it before posting.
        assertThatThrownBy(() -> transfers.transfer(
                        new TransferCommand(main, savings, usd(100), null, key), ledger.caller(client)))
                .isInstanceOfSatisfying(
                        DuplicateRequestException.class,
                        e -> assertThat(e.originalId()).isEqualTo(original.id().value()));
        assertThat(balance(main)).isEqualTo(usd(900));
    }

    @Test
    void aTransferRacingPastTheClaimIsStoppedByTheTransfersUniqueKey() throws Exception {
        IdempotencyKey key = key();
        Callable<IdempotentResult<Transfer>> transfer = () ->
                transfers.transfer(new TransferCommand(main, savings, usd(100), null, key), ledger.caller(client));

        IdempotentResult<Transfer> original = raceTwo(transfer, t -> t.id().value());

        assertThat(balance(main)).isEqualTo(usd(900));
        assertThat(rowsWithKey("transfers", key)).isOne();
        assertThat(original.value().amount()).isEqualTo(usd(100));
        assertThat(invariantChecker.check().isClean()).isTrue();
    }

    @Test
    void aFundingRacingPastTheClaimIsStoppedByTheFundingsUniqueKey() throws Exception {
        IdempotencyKey key = key();
        Caller admin = ledger.caller(client, EnumSet.allOf(Scope.class));
        Callable<IdempotentResult<Funding>> funding =
                () -> fundings.fund(new FundingCommand(main, usd(100), "BANK-REF-1", key), admin);

        raceTwo(funding, f -> f.id().value());

        assertThat(balance(main)).isEqualTo(usd(1100));
        assertThat(rowsWithKey("fundings", key)).isOne();
        assertThat(invariantChecker.check().isClean()).isTrue();
    }

    /**
     * Runs the request twice, overlapping. The first runs inside a transaction this test holds open, so it has posted
     * and inserted its row but not committed. The second can't see that row, so it gets past the service's own look for
     * the key, posts, and waits for the first's lock on the account. Once the first commits, the second's insert hits
     * the unique key, everything it did rolls back, and it reports the first as the original.
     *
     * @param idOf the id of what the request created, which the second request's error must name
     * @return the first request's result
     */
    private <T> IdempotentResult<T> raceTwo(Callable<IdempotentResult<T>> request, Function<T, UUID> idOf)
            throws Exception {
        CountDownLatch firstHasMovedTheMoney = new CountDownLatch(1);
        CountDownLatch commitTheFirst = new CountDownLatch(1);
        Future<IdempotentResult<T>> first =
                threads.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    IdempotentResult<T> result = call(request);
                    firstHasMovedTheMoney.countDown();
                    awaitUninterruptibly(commitTheFirst);
                    return result;
                }));
        assertThat(firstHasMovedTheMoney.await(WAIT_LIMIT.toSeconds(), SECONDS)).isTrue();

        Future<IdempotentResult<T>> second = threads.submit(request);
        awaitASessionWaitingForALock(owner);
        commitTheFirst.countDown();

        IdempotentResult<T> original = first.get(WAIT_LIMIT.toSeconds(), SECONDS);
        UUID originalId = idOf.apply(original.value());
        assertThatThrownBy(() -> second.get(WAIT_LIMIT.toSeconds(), SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        DuplicateRequestException.class,
                        e -> assertThat(e.originalId()).isEqualTo(originalId));
        return original;
    }

    private static <T> T call(Callable<T> request) {
        try {
            return request.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_LIMIT.toSeconds(), SECONDS)) {
                throw new IllegalStateException("the test never released this transaction");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long rowsWithKey(String table, IdempotencyKey key) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE client_id = :clientId AND idempotency_key = :key")
                .param("clientId", client.value())
                .param("key", key.value())
                .query(Long.class)
                .single();
    }

    private Money balance(AccountId account) {
        return queries.balance(account).posted();
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey("test-" + UUID.randomUUID());
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
