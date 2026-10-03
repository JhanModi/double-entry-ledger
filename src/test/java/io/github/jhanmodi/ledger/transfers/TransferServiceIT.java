package io.github.jhanmodi.ledger.transfers;

import static io.github.jhanmodi.ledger.money.CurrencyCode.EUR;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.Caller;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.clients.Scope;
import io.github.jhanmodi.ledger.clients.ScopeRequiredException;
import io.github.jhanmodi.ledger.ledger.AccountClosedException;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountPurpose;
import io.github.jhanmodi.ledger.ledger.AccountService;
import io.github.jhanmodi.ledger.ledger.InsufficientFundsException;
import io.github.jhanmodi.ledger.ledger.InvariantChecker;
import io.github.jhanmodi.ledger.ledger.LedgerFixtures;
import io.github.jhanmodi.ledger.ledger.LedgerQueries;
import io.github.jhanmodi.ledger.ledger.PostingService;
import io.github.jhanmodi.ledger.money.CurrencyMismatchException;
import io.github.jhanmodi.ledger.money.Money;
import io.github.jhanmodi.ledger.transfers.TransferAccountNotFoundException.Side;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transfers against a real database: money moves, every rejection moves and records nothing, a retry never moves money
 * twice (ADR-0019), and concurrent transfers never overdraw an account. The full concurrency suite is M5's; the race
 * tests here cover what M4b claims.
 *
 * <p>Alice's business owns the accounts. Each test starts her main USD account with 10.00 USD.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransferServiceIT {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

    @Autowired
    TransferService transfers;

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
    ClientId alice;
    AccountId main;
    AccountId savings;

    @BeforeEach
    void setUp() {
        ledger = new LedgerFixtures(accountService, postingService, clientService);
        alice = ledger.client();
        main = ledger.customer(USD);
        savings = ledger.customer(USD);
        ledger.fund(ledger.bank(USD), main, usd(1000));
    }

    // --- Moving money ---

    @Test
    void movesMoneyBetweenTwoOfTheClientsAccounts() {
        Caller caller = ledger.caller(alice);
        IdempotencyKey key = key();

        Transfer transfer = transfers.transfer(new TransferCommand(main, savings, usd(300), "rent", key), caller);

        assertThat(balance(main)).isEqualTo(usd(700));
        assertThat(balance(savings)).isEqualTo(usd(300));
        assertThat(transfer.source()).isEqualTo(main);
        assertThat(transfer.destination()).isEqualTo(savings);
        assertThat(transfer.amount()).isEqualTo(usd(300));
        assertThat(transfer.description()).isEqualTo("rent");
        assertThat(transfer.idempotencyKey()).isEqualTo(key);
        assertThat(entries(transfer)).containsExactlyInAnyOrder("DEBIT " + main + " 300", "CREDIT " + savings + " 300");
        assertThat(transfers.find(alice, transfer.id())).isEqualTo(transfer);
        assertThat(auditedIn(caller)).containsExactly("TRANSFER_CREATED " + transfer.id());
    }

    @Test
    void theDescriptionIsOptional() {
        Transfer transfer = transfers.transfer(new TransferCommand(main, savings, usd(1), null, key()), caller());

        assertThat(transfer.description()).isNull();
        assertThat(balance(savings)).isEqualTo(usd(1));
    }

    // --- Rejected: nothing moves, and nothing is recorded ---

    @Test
    void anInsufficientBalanceMovesNothingAndRecordsNothing() {
        Caller caller = ledger.caller(alice);
        IdempotencyKey key = key();

        assertThatThrownBy(() -> transfers.transfer(new TransferCommand(main, savings, usd(1001), null, key), caller))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balance(main)).isEqualTo(usd(1000));
        assertThat(balance(savings)).isEqualTo(usd(0));
        assertThat(transfersWithKey(key)).isZero();
        assertThat(auditedIn(caller)).isEmpty();
    }

    @Test
    void anAccountTheClientDoesNotOwnIsNotFoundAndTheErrorSaysWhichSide() {
        AccountId bobs = ledger.customer(clientService.createClient("bob"), USD);
        AccountId settlement =
                queries.systemAccount(AccountPurpose.BANK_SETTLEMENT, USD).id();
        AccountId missing = new AccountId(UUID.randomUUID());

        assertNotFound(bobs, savings, Side.SOURCE);
        assertNotFound(main, bobs, Side.DESTINATION);
        assertNotFound(main, missing, Side.DESTINATION);
        assertNotFound(settlement, main, Side.SOURCE);
        assertThat(balance(main)).isEqualTo(usd(1000));
    }

    @Test
    void bothAccountsAndTheAmountMustBeInTheSameCurrency() {
        AccountId euros = ledger.customer(EUR);

        assertThatThrownBy(() -> transfers.transfer(new TransferCommand(main, euros, usd(100), null, key()), caller()))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> transfers.transfer(
                        new TransferCommand(main, savings, Money.of(100, EUR), null, key()), caller()))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThat(balance(main)).isEqualTo(usd(1000));
    }

    @Test
    void aClosedAccountCannotReceive() {
        // There's no endpoint to close an account yet; the app's login may update status (ADR-0015).
        jdbc.sql("UPDATE accounts SET status = 'CLOSED' WHERE id = :id")
                .param("id", savings.value())
                .update();

        assertThatThrownBy(
                        () -> transfers.transfer(new TransferCommand(main, savings, usd(100), null, key()), caller()))
                .isInstanceOf(AccountClosedException.class);
        assertThat(balance(main)).isEqualTo(usd(1000));
    }

    @Test
    void aKeyWithoutTheWriteScopeCannotTransfer() {
        Caller readOnly = ledger.caller(alice, EnumSet.of(Scope.READ));

        assertThatThrownBy(
                        () -> transfers.transfer(new TransferCommand(main, savings, usd(100), null, key()), readOnly))
                .isInstanceOf(ScopeRequiredException.class);
        assertThat(balance(main)).isEqualTo(usd(1000));
    }

    // --- Retries (ADR-0019) ---

    @Test
    void theSameKeyTwiceMovesTheMoneyOnce() {
        IdempotencyKey key = key();
        Transfer original = transfers.transfer(new TransferCommand(main, savings, usd(300), "first", key), caller());

        // A retry, even one with a different body, is refused and points at the original. (M6 makes a different body a
        // 422 and replays an identical one.)
        assertThatThrownBy(
                        () -> transfers.transfer(new TransferCommand(main, savings, usd(500), "retry", key), caller()))
                .isInstanceOfSatisfying(
                        DuplicateRequestException.class,
                        e -> assertThat(e.originalId()).isEqualTo(original.id().value()));
        assertThat(balance(main)).isEqualTo(usd(700));
        assertThat(transfersWithKey(key)).isOne();
    }

    @Test
    void aRetryIsRecognisedEvenAfterTheMoneyIsSpent() {
        IdempotencyKey key = key();
        transfers.transfer(new TransferCommand(main, savings, usd(1000), null, key), caller());

        // The key is checked before anything else, so the retry gets "duplicate", not "insufficient funds".
        assertThatThrownBy(() -> transfers.transfer(new TransferCommand(main, savings, usd(1000), null, key), caller()))
                .isInstanceOf(DuplicateRequestException.class);
    }

    @Test
    void aDuplicateThatLosesTheRaceIsStoppedByTheUniqueKeyAndNeverApplied() throws Exception {
        IdempotencyKey key = key();
        Caller firstCaller = caller();
        Caller secondCaller = caller();
        CountDownLatch firstHasMovedTheMoney = new CountDownLatch(1);
        CountDownLatch commitTheFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // The first request moves the money inside a transaction this test holds open, so it isn't committed yet.
            Future<Transfer> first = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                Transfer transfer =
                        transfers.transfer(new TransferCommand(main, savings, usd(100), null, key), firstCaller);
                firstHasMovedTheMoney.countDown();
                awaitUninterruptibly(commitTheFirst);
                return transfer;
            }));
            assertThat(firstHasMovedTheMoney.await(WAIT_LIMIT.toSeconds(), SECONDS))
                    .isTrue();

            // The second can't see the first's uncommitted row, so it passes the key check, starts posting, and then
            // has to wait for the first's lock on the balances.
            Future<Transfer> second = pool.submit(
                    () -> transfers.transfer(new TransferCommand(main, savings, usd(100), null, key), secondCaller));
            awaitASessionWaitingForALock();
            commitTheFirst.countDown();

            // Once the first commits, the second's insert hits the unique key, rolls back, and reports the original.
            Transfer original = first.get(WAIT_LIMIT.toSeconds(), SECONDS);
            assertThatThrownBy(() -> second.get(WAIT_LIMIT.toSeconds(), SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(
                            DuplicateRequestException.class,
                            e -> assertThat(e.originalId())
                                    .isEqualTo(original.id().value()));
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance(main)).isEqualTo(usd(900));
        assertThat(transfersWithKey(key)).isOne();
    }

    @Test
    void identicalRequestsSentAtOnceMoveTheMoneyOnce() throws Exception {
        IdempotencyKey key = key();

        List<Object> outcomes =
                runAtOnce(8, i -> transferCall(new TransferCommand(main, savings, usd(100), null, key), caller()));

        List<Transfer> succeeded = ofType(outcomes, Transfer.class);
        assertThat(succeeded).hasSize(1);
        assertThat(ofType(outcomes, DuplicateRequestException.class))
                .hasSize(7)
                .allSatisfy(e -> assertThat(e.originalId())
                        .isEqualTo(succeeded.getFirst().id().value()));
        assertThat(balance(main)).isEqualTo(usd(900));
        assertThat(transfersWithKey(key)).isOne();
    }

    // --- Concurrency smoke test (M5 has the full suite) ---

    @Test
    void concurrentTransfersNeverOverdrawTheSource() throws Exception {
        // 20 transfers of 1.00 from 10.00, all at once: exactly 10 can succeed.
        List<Object> outcomes =
                runAtOnce(20, i -> transferCall(new TransferCommand(main, savings, usd(100), null, key()), caller()));

        assertThat(ofType(outcomes, Transfer.class)).hasSize(10);
        assertThat(ofType(outcomes, InsufficientFundsException.class)).hasSize(10);
        assertThat(balance(main)).isEqualTo(usd(0));
        assertThat(balance(savings)).isEqualTo(usd(1000));
        assertThat(invariantChecker.check().isClean()).isTrue();
    }

    // --- Reading a transfer back ---

    @Test
    void aTransferIsFoundOnlyByTheClientThatMadeIt() {
        Transfer transfer = transfers.transfer(new TransferCommand(main, savings, usd(1), null, key()), caller());
        ClientId bob = clientService.createClient("bob");

        assertThat(transfers.find(alice, transfer.id())).isEqualTo(transfer);
        assertThatThrownBy(() -> transfers.find(bob, transfer.id())).isInstanceOf(TransferNotFoundException.class);
        assertThatThrownBy(() -> transfers.find(alice, new TransferId(UUID.randomUUID())))
                .isInstanceOf(TransferNotFoundException.class);
    }

    // --- helpers ---

    private void assertNotFound(AccountId source, AccountId destination, Side side) {
        assertThatThrownBy(() ->
                        transfers.transfer(new TransferCommand(source, destination, usd(1), null, key()), caller()))
                .isInstanceOfSatisfying(
                        TransferAccountNotFoundException.class,
                        e -> assertThat(e.side()).isEqualTo(side));
    }

    /** Alice making a new request with her read and write key. */
    private Caller caller() {
        return ledger.caller(alice);
    }

    private Callable<Object> transferCall(TransferCommand command, Caller caller) {
        return () -> transfers.transfer(command, caller);
    }

    /**
     * Starts the tasks on separate threads, releases them together, and returns each one's result or exception. Tasks
     * are built on this thread, because the fixtures aren't thread-safe.
     */
    private static List<Object> runAtOnce(int count, IntFunction<Callable<Object>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<Object> work = task.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    outcomes.add(future.get(WAIT_LIMIT.toSeconds(), SECONDS));
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> List<T> ofType(List<Object> outcomes, Class<T> type) {
        return outcomes.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * Waits until some database session is blocked waiting for a lock. Polls a condition Postgres reports, rather than
     * sleeping for a guessed time, so it's as fast as the database and fails clearly if the wait never happens.
     */
    private void awaitASessionWaitingForALock() {
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

    private Money balance(AccountId account) {
        return queries.balance(account).posted();
    }

    /** The ledger entries behind a transfer, as "DIRECTION account amount". */
    private List<String> entries(Transfer transfer) {
        return jdbc.sql(
                        "SELECT direction || ' ' || account_id || ' ' || amount FROM entries WHERE transaction_id = :id")
                .param("id", transfer.ledgerTransactionId().value())
                .query(String.class)
                .list();
    }

    private long transfersWithKey(IdempotencyKey key) {
        return jdbc.sql("SELECT count(*) FROM transfers WHERE client_id = :clientId AND idempotency_key = :key")
                .param("clientId", alice.value())
                .param("key", key.value())
                .query(Long.class)
                .single();
    }

    /** What was audited in this caller's request, as "ACTION target", read as the owner. */
    private List<String> auditedIn(Caller caller) {
        return owner.jdbc()
                .sql("SELECT action || ' ' || target_id FROM audit_log WHERE request_id = :requestId")
                .param("requestId", caller.requestId().value())
                .query(String.class)
                .list();
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey("test-" + UUID.randomUUID());
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
