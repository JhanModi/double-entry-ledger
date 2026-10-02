package io.github.jhanmodi.ledger.ledger;

import static io.github.jhanmodi.ledger.ledger.NewEntry.credit;
import static io.github.jhanmodi.ledger.ledger.NewEntry.debit;
import static io.github.jhanmodi.ledger.money.CurrencyCode.EUR;
import static io.github.jhanmodi.ledger.money.CurrencyCode.KWD;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * The specification for {@link InvariantChecker}: the owner's M3a exercise. These fail until both queries in
 * InvariantChecker are written.
 *
 * <p>Each test starts from an empty ledger. {@code @DirtiesContext} gives this class a fresh Spring context, and with it
 * a fresh Postgres container, and {@code @Transactional} rolls every test back when it finishes. Because nothing is
 * committed, the deferred balance triggers never fire, so these tests can plant broken data that the database would
 * refuse to commit. That's how they simulate the bugs the checker exists to catch.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Transactional
class InvariantCheckerIT {

    @Autowired
    InvariantChecker checker;

    @Autowired
    AccountService accountService;

    @Autowired
    PostingService postingService;

    @Autowired
    JdbcClient jdbc;

    LedgerFixtures ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerFixtures(accountService, postingService);
    }

    // --- A correct ledger is reported clean ---

    @Test
    void anEmptyLedgerIsClean() {
        InvariantReport report = checker.check();

        assertThat(report.balanceMismatches()).isEmpty();
        assertThat(report.currencyImbalances()).isEmpty();
        assertThat(report.isClean()).isTrue();
    }

    @Test
    void aLedgerOfValidPostingsIsClean() {
        AccountId usdBank = ledger.bank(USD);
        AccountId eurBank = ledger.bank(EUR);
        AccountId alice = ledger.customer(USD);
        AccountId bob = ledger.customer(USD);
        AccountId carol = ledger.customer(EUR);
        AccountId fees = accountService.openSystemAccount(AccountType.REVENUE, USD);
        ledger.fund(usdBank, alice, usd(1000));
        ledger.transfer(alice, bob, usd(300));
        ledger.fund(eurBank, carol, Money.of(500, EUR));
        postingService.post(new PostingRequest(
                LedgerTransactionType.TRANSFER, "fee", List.of(debit(alice, usd(50)), credit(fees, usd(50)))));

        InvariantReport report = checker.check();

        assertThat(report.balanceMismatches()).isEmpty();
        assertThat(report.currencyImbalances()).isEmpty();
    }

    @Test
    void customersWithNoEntriesAndAZeroBalanceAreClean() {
        ledger.customer(USD);
        ledger.customer(EUR);

        assertThat(checker.check().balanceMismatches()).isEmpty();
    }

    @Test
    void systemAccountsAreNeverReported() {
        // Money moving only between system accounts, which have no cached balance at all.
        AccountId bankA = ledger.bank(USD);
        AccountId bankB = ledger.bank(USD);
        ledger.transfer(bankA, bankB, usd(400));

        assertThat(checker.check().balanceMismatches()).isEmpty();
    }

    // --- Cached balances that disagree with the entries ---

    @Test
    void reportsACustomerWhoseCachedBalanceDisagreesWithItsEntries() {
        AccountId alice = ledger.customer(USD);
        AccountId bob = ledger.customer(USD);
        ledger.fund(ledger.bank(USD), alice, usd(1000));
        ledger.transfer(alice, bob, usd(300));
        // Alice's entries: a credit of 1000 and a debit of 300, so her balance is 700. Make the cache say 701.
        setCachedBalance(alice, 701);

        InvariantReport report = checker.check();

        assertThat(report.balanceMismatches())
                .containsExactly(new BalanceMismatch(alice, 701, BigInteger.valueOf(700)));
        assertThat(report.currencyImbalances()).isEmpty();
    }

    @Test
    void reportsACorruptedBalanceOnAnAccountWithNoEntries() {
        AccountId alice = ledger.customer(USD);
        setCachedBalance(alice, 500);

        assertThat(checker.check().balanceMismatches())
                .containsExactly(new BalanceMismatch(alice, 500, BigInteger.ZERO));
    }

    @Test
    void reportsOnlyTheAccountsThatAreWrong() {
        AccountId bank = ledger.bank(USD);
        AccountId alice = ledger.customer(USD);
        AccountId bob = ledger.customer(USD);
        AccountId carol = ledger.customer(USD);
        ledger.fund(bank, alice, usd(100));
        ledger.fund(bank, bob, usd(200));
        ledger.fund(bank, carol, usd(300));
        setCachedBalance(bob, 199);

        assertThat(checker.check().balanceMismatches())
                .containsExactly(new BalanceMismatch(bob, 199, BigInteger.valueOf(200)));
    }

    // --- Currencies whose debits and credits don't add up ---

    @Test
    void reportsACurrencyWhoseDebitsExceedItsCredits() {
        AccountId bank = ledger.bank(USD);
        ledger.fund(bank, ledger.customer(USD), usd(1000));
        // A stray debit of 50, as if a bug had written one side of a transaction and not the other.
        plant(debit(bank, usd(50)));

        InvariantReport report = checker.check();

        assertThat(report.currencyImbalances())
                .containsExactly(new CurrencyImbalance(USD, BigInteger.valueOf(1050), BigInteger.valueOf(1000)));
        assertThat(report.balanceMismatches()).isEmpty();
    }

    @Test
    void reportsACurrencyThatHasDebitsButNoCreditsAtAll() {
        plant(debit(ledger.bank(KWD), Money.of(100, KWD)));

        assertThat(checker.check().currencyImbalances())
                .containsExactly(new CurrencyImbalance(KWD, BigInteger.valueOf(100), BigInteger.ZERO));
    }

    @Test
    void checksEachCurrencySeparately() {
        // 100 debited in USD and 100 credited in EUR: the grand totals match, but both currencies are broken.
        plant(debit(ledger.bank(USD), usd(100)), credit(ledger.bank(EUR), Money.of(100, EUR)));

        assertThat(checker.check().currencyImbalances())
                .containsExactlyInAnyOrder(
                        new CurrencyImbalance(USD, BigInteger.valueOf(100), BigInteger.ZERO),
                        new CurrencyImbalance(EUR, BigInteger.ZERO, BigInteger.valueOf(100)));
    }

    @Test
    void reportsEveryProblemAtOnce() {
        AccountId alice = ledger.customer(EUR);
        setCachedBalance(alice, 42);
        plant(credit(ledger.bank(USD), usd(7)));

        InvariantReport report = checker.check();

        assertThat(report.balanceMismatches()).containsExactly(new BalanceMismatch(alice, 42, BigInteger.ZERO));
        assertThat(report.currencyImbalances())
                .containsExactly(new CurrencyImbalance(USD, BigInteger.ZERO, BigInteger.valueOf(7)));
        assertThat(report.isClean()).isFalse();
    }

    // --- helpers that simulate bugs (all rolled back with the test) ---

    /** Overwrites a customer's cached balance, as a buggy write path might. */
    private void setCachedBalance(AccountId account, long minorUnits) {
        jdbc.sql("UPDATE accounts SET posted_balance = :balance WHERE id = :id")
                .param("balance", minorUnits)
                .param("id", account.value())
                .update();
    }

    /** Writes a ledger transaction with exactly these entries, balanced or not, bypassing the posting service. */
    private void plant(NewEntry... entries) {
        UUID transactionId = jdbc.sql(
                        "INSERT INTO ledger_transactions (type, effective_date) VALUES ('TRANSFER', current_date) RETURNING id")
                .query(UUID.class)
                .single();
        for (NewEntry entry : entries) {
            jdbc.sql("""
                            INSERT INTO entries (transaction_id, account_id, currency, direction, amount)
                            VALUES (:transactionId, :accountId, :currency, :direction, :amount)
                            """)
                    .param("transactionId", transactionId)
                    .param("accountId", entry.accountId().value())
                    .param("currency", entry.amount().currency().name())
                    .param("direction", entry.direction().name())
                    .param("amount", entry.amount().minorUnits())
                    .update();
        }
    }

    private static Money usd(long cents) {
        return Money.of(cents, CurrencyCode.USD);
    }
}
