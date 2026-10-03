package io.github.jhanmodi.ledger.ledger;

import static io.github.jhanmodi.ledger.DatabaseLocks.awaitASessionWaitingForALock;
import static io.github.jhanmodi.ledger.DatabaseLocks.lockAccount;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.DatabaseLocks;
import io.github.jhanmodi.ledger.HeldTransaction;
import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.money.Money;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How a posting takes its locks (ADR-0005, ADR-0022): customer accounts in ascending id order, status and balances
 * re-read under the lock, system accounts never locked, and no wait longer than the lock timeout.
 *
 * <p>Each test creates one exact interleaving. The owner holds a lock in a {@link HeldTransaction}, the posting runs on
 * another thread, and the test waits until Postgres reports the posting blocked before it releases the lock.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostingLocksIT {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

    @Autowired
    PostingService postingService;

    @Autowired
    AccountService accountService;

    @Autowired
    ClientService clientService;

    @Autowired
    LedgerQueries queries;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerDatabase owner;

    LedgerFixtures ledger;
    AccountId bank;
    ExecutorService postingThread;

    @BeforeEach
    void setUp() {
        ledger = new LedgerFixtures(accountService, postingService, clientService);
        bank = ledger.bank(USD);
        postingThread = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        postingThread.shutdownNow();
    }

    @Test
    void customerAccountsAreLockedInAscendingIdOrderWhateverOrderTheEntriesAreIn() throws Exception {
        List<AccountId> pair = List.of(ledger.customer(USD), ledger.customer(USD)).stream()
                .sorted()
                .toList();
        AccountId lower = pair.get(0);
        AccountId higher = pair.get(1);
        ledger.fund(bank, higher, usd(1000));

        try (HeldTransaction holder = HeldTransaction.start(owner, sql -> lockAccount(sql, higher))) {
            // The entries name the higher id first. Locking in the entries' order would mean waiting for the higher id
            // while holding nothing; locking in id order means the lower one is already held while it waits.
            Future<LedgerTransactionId> posting = postingThread.submit(() -> ledger.transfer(higher, lower, usd(100)));
            awaitASessionWaitingForALock(owner);

            assertThat(DatabaseLocks.isLocked(owner, lower))
                    .as("the posting holds the lower id while it waits for the higher one")
                    .isTrue();

            holder.rollback();
            posting.get(WAIT_LIMIT.toSeconds(), SECONDS);
        }
        assertThat(balance(lower)).isEqualTo(usd(100));
        assertThat(balance(higher)).isEqualTo(usd(900));
    }

    @Test
    void aPostingThatWaitsSeesAnAccountClosedWhileItWaitedAndIsRejected() {
        AccountId source = ledger.customer(USD);
        AccountId destination = ledger.customer(USD);
        ledger.fund(bank, source, usd(1000));

        // Another transaction closes the destination and keeps it locked until it commits. (There's no endpoint to
        // close an account yet; this is the race that one would create.)
        try (HeldTransaction closer = HeldTransaction.start(owner, sql -> close(sql, destination))) {
            Future<LedgerTransactionId> posting =
                    postingThread.submit(() -> ledger.transfer(source, destination, usd(100)));
            awaitASessionWaitingForALock(owner);
            closer.commit();

            // The status is read under the lock, so the posting sees the commit it waited for.
            assertThatThrownBy(() -> posting.get(WAIT_LIMIT.toSeconds(), SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(
                            AccountClosedException.class,
                            e -> assertThat(e.accountId()).isEqualTo(destination));
        }
        assertThat(balance(source)).isEqualTo(usd(1000));
        assertThat(entryCount(destination)).isZero();
    }

    @Test
    void aLockHeldLongerThanTheTimeoutMakesThePostingGiveUpAndWriteNothing() {
        AccountId customer = ledger.customer(USD);

        try (HeldTransaction holder = HeldTransaction.start(owner, sql -> lockAccount(sql, customer))) {
            Future<LedgerTransactionId> posting = postingThread.submit(() -> ledger.fund(bank, customer, usd(100)));

            assertThatThrownBy(() -> posting.get(WAIT_LIMIT.toSeconds(), SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(AccountBusyException.class);
        }
        assertThat(balance(customer)).isEqualTo(usd(0));
        assertThat(entryCount(bank, customer)).isZero();
    }

    @Test
    void systemAccountsAreNeverLocked() {
        AccountId customer = ledger.customer(USD);

        // Every funding touches its currency's settlement account. If postings locked it, all fundings in a currency
        // would queue behind one another. With the bank's row locked elsewhere, a funding still goes straight through;
        // if it tried to lock the bank, it would wait and then fail with AccountBusyException.
        try (HeldTransaction holder = HeldTransaction.start(owner, sql -> lockAccount(sql, bank))) {
            ledger.fund(bank, customer, usd(100));
        }
        assertThat(balance(customer)).isEqualTo(usd(100));
    }

    @Test
    void theLockTimeoutIsSetForThePostingsTransactionOnly() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        Map<String, Object> during = transactions.execute(status -> {
            ledger.fund(bank, ledger.customer(USD), usd(1));
            return lockTimeoutAndConnection();
        });
        Map<String, Object> after = transactions.execute(status -> lockTimeoutAndConnection());

        // The default in application.yml (D2-A in the M5 proposal).
        assertThat(during).containsEntry("lock_timeout", "2s");
        // Postgres's default: no limit. Checked on the same pooled connection, where a setting that leaked past the
        // transaction would still be visible.
        assertThat(after.get("pid")).isEqualTo(during.get("pid"));
        assertThat(after).containsEntry("lock_timeout", "0");
    }

    // --- helpers ---

    private static void close(JdbcClient sql, AccountId account) {
        sql.sql("UPDATE accounts SET status = 'CLOSED' WHERE id = :id")
                .param("id", account.value())
                .update();
    }

    private Map<String, Object> lockTimeoutAndConnection() {
        return jdbc.sql("SELECT current_setting('lock_timeout') AS lock_timeout, pg_backend_pid() AS pid")
                .query()
                .singleRow();
    }

    private Money balance(AccountId account) {
        return queries.balance(account).posted();
    }

    private long entryCount(AccountId... accounts) {
        return jdbc.sql("SELECT count(*) FROM entries WHERE account_id IN (:ids)")
                .param("ids", List.of(accounts).stream().map(AccountId::value).toList())
                .query(Long.class)
                .single();
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
