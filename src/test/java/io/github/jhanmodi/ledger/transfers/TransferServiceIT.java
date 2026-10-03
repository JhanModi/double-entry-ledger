package io.github.jhanmodi.ledger.transfers;

import static io.github.jhanmodi.ledger.DatabaseLocks.awaitASessionWaitingForALock;
import static io.github.jhanmodi.ledger.DatabaseLocks.lockAccount;
import static io.github.jhanmodi.ledger.money.CurrencyCode.EUR;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.HeldTransaction;
import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.Caller;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.clients.Scope;
import io.github.jhanmodi.ledger.clients.ScopeRequiredException;
import io.github.jhanmodi.ledger.idempotency.IdempotencyKey;
import io.github.jhanmodi.ledger.idempotency.IdempotencyKeyReusedException;
import io.github.jhanmodi.ledger.idempotency.IdempotentResult;
import io.github.jhanmodi.ledger.idempotency.RequestInProgressException;
import io.github.jhanmodi.ledger.ledger.AccountClosedException;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountPurpose;
import io.github.jhanmodi.ledger.ledger.AccountService;
import io.github.jhanmodi.ledger.ledger.InsufficientFundsException;
import io.github.jhanmodi.ledger.ledger.InvariantChecker;
import io.github.jhanmodi.ledger.ledger.LedgerFixtures;
import io.github.jhanmodi.ledger.ledger.LedgerQueries;
import io.github.jhanmodi.ledger.ledger.PostingService;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transfers against a real database: money moves, every rejection moves and records nothing, a retry replays the
 * original and never moves money twice (ADR-0023), concurrent transfers never overdraw an account, and a transfer
 * aborted to break a deadlock is retried (ADR-0022). The full concurrency suite is ConcurrencyIT.
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
    ExecutorService secondRequest;

    @BeforeEach
    void setUp() {
        ledger = new LedgerFixtures(accountService, postingService, clientService);
        alice = ledger.client();
        main = ledger.customer(USD);
        savings = ledger.customer(USD);
        ledger.fund(ledger.bank(USD), main, usd(1000));
        secondRequest = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        secondRequest.shutdownNow();
    }

    // --- Moving money ---

    @Test
    void movesMoneyBetweenTwoOfTheClientsAccounts() {
        Caller caller = ledger.caller(alice);
        IdempotencyKey key = key();

        IdempotentResult<Transfer> result =
                transfers.transfer(new TransferCommand(main, savings, usd(300), "rent", key), caller);

        Transfer transfer = result.value();
        assertThat(result.replayed()).isFalse();
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
        assertThat(claimsFor(key)).containsExactly("TRANSFER");
    }

    @Test
    void theDescriptionIsOptional() {
        Transfer transfer = transfers
                .transfer(new TransferCommand(main, savings, usd(1), null, key()), caller())
                .value();

        assertThat(transfer.description()).isNull();
        assertThat(balance(savings)).isEqualTo(usd(1));
    }

    // --- Rejected: nothing moves, nothing is recorded, and the key stays free ---

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
        assertThat(claimsFor(key)).as("the claim rolled back with the rest").isEmpty();
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
                .isInstanceOf(WrongCurrencyException.class);
        assertThatThrownBy(() -> transfers.transfer(
                        new TransferCommand(main, savings, Money.of(100, EUR), null, key()), caller()))
                .isInstanceOf(WrongCurrencyException.class);
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
        IdempotencyKey key = key();

        assertThatThrownBy(() -> transfers.transfer(new TransferCommand(main, savings, usd(100), null, key), readOnly))
                .isInstanceOf(ScopeRequiredException.class);
        assertThat(balance(main)).isEqualTo(usd(1000));
        assertThat(claimsFor(key)).as("refused before the key was claimed").isEmpty();
    }

    // --- Retries, one after another (ADR-0023) ---

    @Test
    void aRetryOfTheSameRequestReplaysTheOriginalAndMovesNothing() {
        IdempotencyKey key = key();
        Transfer original = transfers
                .transfer(new TransferCommand(main, savings, usd(300), "rent", key), caller())
                .value();
        Caller retrying = caller();

        IdempotentResult<Transfer> retry =
                transfers.transfer(new TransferCommand(main, savings, usd(300), "rent", key), retrying);

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.value()).isEqualTo(original);
        assertThat(balance(main)).isEqualTo(usd(700));
        assertThat(transfersWithKey(key)).isOne();
        assertThat(auditedIn(retrying))
                .as("a replay does nothing, so there's nothing to audit")
                .isEmpty();
    }

    @Test
    void aRetryIsReplayedEvenAfterTheMoneyIsSpent() {
        IdempotencyKey key = key();
        Transfer original = transfers
                .transfer(new TransferCommand(main, savings, usd(1000), null, key), caller())
                .value();

        // The key is claimed before anything else, so the retry gets the original, not "insufficient funds".
        IdempotentResult<Transfer> retry =
                transfers.transfer(new TransferCommand(main, savings, usd(1000), null, key), caller());

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.value()).isEqualTo(original);
    }

    @Test
    void theSameKeyWithADifferentRequestIsRefusedAndMovesNothing() {
        IdempotencyKey key = key();
        transfers.transfer(new TransferCommand(main, savings, usd(300), "rent", key), caller());
        Caller reusing = caller();

        assertThatThrownBy(() -> transfers.transfer(new TransferCommand(main, savings, usd(500), "rent", key), reusing))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> transfers.transfer(new TransferCommand(main, savings, usd(300), "food", key), reusing))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(balance(main)).isEqualTo(usd(700));
        assertThat(transfersWithKey(key)).isOne();
        assertThat(auditedIn(reusing)).isEmpty();
    }

    @Test
    void aFailedRequestLeavesTheKeyFreeSoARetryRunsAgain() {
        IdempotencyKey key = key();
        TransferCommand command = new TransferCommand(main, savings, usd(1500), null, key);
        assertThatThrownBy(() -> transfers.transfer(command, caller())).isInstanceOf(InsufficientFundsException.class);

        // Only successes are remembered (ADR-0023, D1): once the money is there, the same request goes through.
        ledger.fund(ledger.bank(USD), main, usd(500));
        IdempotentResult<Transfer> retry = transfers.transfer(command, caller());

        assertThat(retry.replayed()).isFalse();
        assertThat(balance(main)).isEqualTo(usd(0));
        assertThat(balance(savings)).isEqualTo(usd(1500));
    }

    @Test
    void aRetryAfterItsClaimWasDeletedIsRefusedWithTheOriginalsIdAndMovesNothing() {
        IdempotencyKey key = key();
        Transfer original = transfers
                .transfer(new TransferCommand(main, savings, usd(1000), null, key), caller())
                .value();
        // What the cleanup does once the claim has expired (ADR-0023, D5).
        deleteClaim(key);

        // The transfer row still has the key, and it's checked before any money moves. The money is spent, so a retry
        // that skipped that check would be refused as "insufficient funds" instead, and the client would never learn
        // that its transfer had in fact gone through.
        assertThatThrownBy(() -> transfers.transfer(new TransferCommand(main, savings, usd(1000), null, key), caller()))
                .isInstanceOfSatisfying(
                        DuplicateRequestException.class,
                        e -> assertThat(e.originalId()).isEqualTo(original.id().value()));
        assertThat(balance(main)).isEqualTo(usd(0));
        assertThat(transfersWithKey(key)).isOne();
        assertThat(claimsFor(key)).as("the refused retry's claim rolled back").isEmpty();
    }

    @Test
    void anotherClientsRequestWithTheSameKeyIsItsOwnAndNeverSeesThisOne() {
        IdempotencyKey key = key();
        Transfer alices = transfers
                .transfer(new TransferCommand(main, savings, usd(300), null, key), caller())
                .value();
        ClientId bob = clientService.createClient("bob");
        AccountId bobsMain = ledger.customer(bob, USD);
        AccountId bobsSavings = ledger.customer(bob, USD);
        ledger.fund(ledger.bank(USD), bobsMain, usd(500));

        // Bob's key is his own: his transfer is carried out, and nothing of Alice's is replayed or named.
        IdempotentResult<Transfer> bobs =
                transfers.transfer(new TransferCommand(bobsMain, bobsSavings, usd(300), null, key), ledger.caller(bob));

        assertThat(bobs.replayed()).isFalse();
        assertThat(bobs.value().id()).isNotEqualTo(alices.id());
        assertThat(balance(bobsSavings)).isEqualTo(usd(300));
        assertThat(balance(savings)).isEqualTo(usd(300));
    }

    // --- The same key, at the same time ---

    @Test
    void aDuplicateWaitsForTheFirstAndReplaysItOnceItCommits() throws Exception {
        IdempotencyKey key = key();
        try (HeldTransfer first = new HeldTransfer(new TransferCommand(main, savings, usd(100), null, key))) {
            // The second waits on the first's uncommitted claim, before it touches any account.
            Future<IdempotentResult<Transfer>> second = secondRequest.submit(
                    () -> transfers.transfer(new TransferCommand(main, savings, usd(100), null, key), caller()));
            awaitASessionWaitingForALock(owner);

            Transfer original = first.commit().value();

            IdempotentResult<Transfer> duplicate = second.get(WAIT_LIMIT.toSeconds(), SECONDS);
            assertThat(duplicate.replayed()).isTrue();
            assertThat(duplicate.value()).isEqualTo(original);
        }
        assertThat(balance(main)).isEqualTo(usd(900));
        assertThat(transfersWithKey(key)).isOne();
    }

    @Test
    void aDuplicateWaitingForAFirstThatFailsCarriesTheRequestOutItself() throws Exception {
        IdempotencyKey key = key();
        try (HeldTransfer first = new HeldTransfer(new TransferCommand(main, savings, usd(100), null, key))) {
            Future<IdempotentResult<Transfer>> second = secondRequest.submit(
                    () -> transfers.transfer(new TransferCommand(main, savings, usd(100), null, key), caller()));
            awaitASessionWaitingForALock(owner);

            // The first rolls back, releasing its claim: as if it had failed after claiming the key.
            first.rollBack();

            IdempotentResult<Transfer> duplicate = second.get(WAIT_LIMIT.toSeconds(), SECONDS);
            assertThat(duplicate.replayed()).isFalse();
        }
        assertThat(balance(main)).isEqualTo(usd(900));
        assertThat(transfersWithKey(key)).isOne();
    }

    @Test
    void aDuplicateStillWaitingAfterTheLockTimeoutIsRefusedAsInProgress() throws Exception {
        IdempotencyKey key = key();
        try (HeldTransfer first = new HeldTransfer(new TransferCommand(main, savings, usd(100), null, key))) {
            Future<IdempotentResult<Transfer>> second = secondRequest.submit(
                    () -> transfers.transfer(new TransferCommand(main, savings, usd(100), null, key), caller()));

            // The first is held for longer than the 2-second lock timeout. The second gives up, through the retry
            // layer, as "in progress": not as a busy account, which would send the client the wrong message.
            assertThatThrownBy(() -> second.get(WAIT_LIMIT.toSeconds(), SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(RequestInProgressException.class);

            first.commit();
        }
        assertThat(balance(main)).isEqualTo(usd(900));
        assertThat(transfersWithKey(key)).isOne();
    }

    @Test
    void identicalRequestsSentAtOnceMoveTheMoneyOnceAndAllGetTheSameTransfer() throws Exception {
        IdempotencyKey key = key();

        List<Object> outcomes =
                runAtOnce(8, i -> transferCall(new TransferCommand(main, savings, usd(100), null, key), caller()));

        List<IdempotentResult<?>> results = results(outcomes);
        assertThat(results).as("every request got the transfer: %s", outcomes).hasSize(8);
        assertThat(results.stream().filter(result -> !result.replayed())).hasSize(1);
        assertThat(results.stream().map(IdempotentResult::value).distinct()).hasSize(1);
        assertThat(balance(main)).isEqualTo(usd(900));
        assertThat(transfersWithKey(key)).isOne();
    }

    // --- Deadlocks (ADR-0022) ---

    @Test
    void aTransferThatPostgresAbortsToBreakADeadlockIsRetriedAndMovesTheMoneyOnce() throws Exception {
        List<AccountId> byId = Stream.of(main, savings).sorted().toList();
        AccountId lower = byId.get(0);
        AccountId higher = byId.get(1);
        IdempotencyKey key = key();
        Caller caller = caller();
        ExecutorService transferThread = Executors.newSingleThreadExecutor();
        try (HeldTransaction other = HeldTransaction.start(owner, sql -> lockAccount(sql, higher))) {
            // The transfer locks the lower id, then waits for the higher one, which the other transaction holds.
            Future<IdempotentResult<Transfer>> transfer = transferThread.submit(
                    () -> transfers.transfer(new TransferCommand(main, savings, usd(100), null, key), caller));
            awaitASessionWaitingForALock(owner);

            // Now the other transaction asks for the lower id, so each waits for the other: a deadlock. The transfer
            // has waited longer, so its deadlock check runs first, and Postgres aborts it (SQLSTATE 40P01).
            Future<Void> otherGetsTheLowerId = other.then(sql -> lockAccount(sql, lower));
            // Only the transfer's rollback can free the lower id, so this completing proves the first attempt was
            // aborted. The other transaction then lets go of both.
            HeldTransaction.await(otherGetsTheLowerId);
            other.rollback();

            // The retry finds both accounts free. Its first attempt's claim rolled back with it, so it claims again.
            assertThat(transfer.get(WAIT_LIMIT.toSeconds(), SECONDS).value().amount())
                    .isEqualTo(usd(100));
        } finally {
            transferThread.shutdownNow();
        }
        assertThat(balance(main)).isEqualTo(usd(900));
        assertThat(balance(savings)).isEqualTo(usd(100));
        assertThat(transfersWithKey(key)).isOne();
        assertThat(claimsFor(key)).hasSize(1);
        assertThat(auditedIn(caller)).hasSize(1);
    }

    // --- Concurrency smoke test (the full suite is ConcurrencyIT) ---

    @Test
    void concurrentTransfersNeverOverdrawTheSource() throws Exception {
        // 20 transfers of 1.00 from 10.00, all at once: exactly 10 can succeed.
        List<Object> outcomes =
                runAtOnce(20, i -> transferCall(new TransferCommand(main, savings, usd(100), null, key()), caller()));

        assertThat(results(outcomes)).hasSize(10);
        assertThat(ofType(outcomes, InsufficientFundsException.class)).hasSize(10);
        assertThat(balance(main)).isEqualTo(usd(0));
        assertThat(balance(savings)).isEqualTo(usd(1000));
        assertThat(invariantChecker.check().isClean()).isTrue();
    }

    // --- Reading a transfer back ---

    @Test
    void aTransferIsFoundOnlyByTheClientThatMadeIt() {
        Transfer transfer = transfers
                .transfer(new TransferCommand(main, savings, usd(1), null, key()), caller())
                .value();
        ClientId bob = clientService.createClient("bob");

        assertThat(transfers.find(alice, transfer.id())).isEqualTo(transfer);
        assertThatThrownBy(() -> transfers.find(bob, transfer.id())).isInstanceOf(TransferNotFoundException.class);
        assertThatThrownBy(() -> transfers.find(alice, new TransferId(UUID.randomUUID())))
                .isInstanceOf(TransferNotFoundException.class);
    }

    // --- helpers ---

    /**
     * Alice's transfer, made inside a transaction this test holds open: its key is claimed and its money posted, but
     * nothing is committed until the test says so. Another request with the same key then has to wait for it.
     */
    private final class HeldTransfer implements AutoCloseable {

        private final CountDownLatch made = new CountDownLatch(1);
        private final CountDownLatch end = new CountDownLatch(1);
        private final ExecutorService thread = Executors.newSingleThreadExecutor();
        private final Future<IdempotentResult<Transfer>> result;
        private volatile boolean commit = true;

        HeldTransfer(TransferCommand command) throws InterruptedException {
            Caller caller = caller();
            result = thread.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                IdempotentResult<Transfer> transfer = transfers.transfer(command, caller);
                made.countDown();
                awaitUninterruptibly(end);
                if (!commit) {
                    status.setRollbackOnly();
                }
                return transfer;
            }));
            assertThat(made.await(WAIT_LIMIT.toSeconds(), SECONDS))
                    .as("the held transfer was made")
                    .isTrue();
        }

        IdempotentResult<Transfer> commit() throws Exception {
            end.countDown();
            return result.get(WAIT_LIMIT.toSeconds(), SECONDS);
        }

        void rollBack() throws Exception {
            commit = false;
            end.countDown();
            result.get(WAIT_LIMIT.toSeconds(), SECONDS);
        }

        @Override
        public void close() {
            end.countDown();
            thread.shutdownNow();
        }
    }

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

    private static List<IdempotentResult<?>> results(List<Object> outcomes) {
        return outcomes.stream()
                .filter(IdempotentResult.class::isInstance)
                .<IdempotentResult<?>>map(IdempotentResult.class::cast)
                .toList();
    }

    private static <T> List<T> ofType(List<Object> outcomes, Class<T> type) {
        return outcomes.stream().filter(type::isInstance).map(type::cast).toList();
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

    /** The operation of Alice's claim on this key, if there is one. */
    private List<String> claimsFor(IdempotencyKey key) {
        return jdbc.sql("SELECT operation FROM idempotency_keys WHERE client_id = :clientId AND idempotency_key = :key")
                .param("clientId", alice.value())
                .param("key", key.value())
                .query(String.class)
                .list();
    }

    /** Deletes Alice's claim on this key, as the owner. */
    private void deleteClaim(IdempotencyKey key) {
        assertThat(owner.jdbc()
                        .sql("DELETE FROM idempotency_keys WHERE client_id = :clientId AND idempotency_key = :key")
                        .param("clientId", alice.value())
                        .param("key", key.value())
                        .update())
                .isOne();
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
