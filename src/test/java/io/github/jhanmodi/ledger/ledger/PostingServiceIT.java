package io.github.jhanmodi.ledger.ledger;

import static io.github.jhanmodi.ledger.ledger.NewEntry.credit;
import static io.github.jhanmodi.ledger.ledger.NewEntry.debit;
import static io.github.jhanmodi.ledger.money.CurrencyCode.EUR;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.money.CurrencyMismatchException;
import io.github.jhanmodi.ledger.money.Money;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostingServiceIT {

    @Autowired
    PostingService postingService;

    @Autowired
    ClientService clientService;

    @Autowired
    AccountService accountService;

    @Autowired
    LedgerQueries queries;

    @Autowired
    JdbcClient jdbc;

    LedgerFixtures ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerFixtures(accountService, postingService, clientService);
    }

    @Test
    void fundingCreditsTheCustomerAndDebitsTheBank() {
        AccountId bank = ledger.bank(USD);
        AccountId customer = ledger.customer(USD);

        ledger.fund(bank, customer, usd(1000));

        // The customer's wallet is a liability, so the credit increases it; the bank account is an asset, so the
        // debit increases it too. Both sides of the platform's balance sheet grew by $10.00.
        assertThat(queries.balance(customer).posted()).isEqualTo(usd(1000));
        assertThat(queries.balance(bank).posted()).isEqualTo(usd(1000));
    }

    @Test
    void aTransferMovesMoneyBetweenCustomers() {
        AccountId alice = ledger.customer(USD);
        AccountId bob = ledger.customer(USD);
        ledger.fund(ledger.bank(USD), alice, usd(1000));

        ledger.transfer(alice, bob, usd(300));

        assertThat(queries.balance(alice).posted()).isEqualTo(usd(700));
        assertThat(queries.balance(bob).posted()).isEqualTo(usd(300));
    }

    @Test
    void writesOneEntryPerLineAndAllOfThemBelongToTheTransaction() {
        AccountId bank = ledger.bank(USD);
        AccountId customer = ledger.customer(USD);

        LedgerTransactionId txn = ledger.fund(bank, customer, usd(250));

        List<String> entries = jdbc.sql(
                        "SELECT account_id || ' ' || direction || ' ' || amount FROM entries WHERE transaction_id = :id")
                .param("id", txn.value())
                .query(String.class)
                .list();
        assertThat(entries).containsExactlyInAnyOrder(bank + " DEBIT 250", customer + " CREDIT 250");
    }

    @Test
    void netsSeveralEntriesOnTheSameAccount() {
        AccountId bank = ledger.bank(USD);
        AccountId customer = ledger.customer(USD);

        // $1.00 in, $0.30 straight back out, in one transaction: the customer ends up $0.70 better off.
        postingService.post(new PostingRequest(
                LedgerTransactionType.TRANSFER,
                null,
                List.of(credit(customer, usd(100)), debit(customer, usd(30)), debit(bank, usd(70)))));

        assertThat(queries.balance(customer).posted()).isEqualTo(usd(70));
    }

    @Test
    void aSystemAccountMayGoNegative() {
        AccountId customer = ledger.customer(USD);
        ledger.fund(ledger.bank(USD), customer, usd(500));
        AccountId emptyBank = ledger.bank(USD);

        // A withdrawal through a bank account that has no recorded cash yet.
        postingService.post(new PostingRequest(
                LedgerTransactionType.TRANSFER, null, List.of(debit(customer, usd(400)), credit(emptyBank, usd(400)))));

        assertThat(queries.balance(emptyBank).posted()).isEqualTo(usd(-400));
    }

    @Test
    void anOverdraftIsRejectedAndLeavesNothingBehind() {
        AccountId alice = ledger.customer(USD);
        AccountId bob = ledger.customer(USD);
        ledger.fund(ledger.bank(USD), alice, usd(100));
        long entriesBefore = entryCount(alice, bob);

        assertThatThrownBy(() -> ledger.transfer(alice, bob, usd(150)))
                .isInstanceOf(InsufficientFundsException.class)
                .extracting(e -> ((InsufficientFundsException) e).accountId())
                .isEqualTo(alice);

        // The entries were inserted before the balance update failed. The whole posting rolled back, so they're gone.
        assertThat(entryCount(alice, bob)).isEqualTo(entriesBefore);
        assertThat(queries.balance(alice).posted()).isEqualTo(usd(100));
        assertThat(queries.balance(bob).posted()).isEqualTo(usd(0));
    }

    @Test
    void rejectsAnUnknownAccount() {
        AccountId bank = ledger.bank(USD);
        AccountId unknown = new AccountId(UUID.randomUUID());

        assertThatThrownBy(() -> ledger.fund(bank, unknown, usd(100))).isInstanceOf(AccountNotFoundException.class);
        assertThat(entryCount(bank)).isZero();
    }

    @Test
    void rejectsAClosedAccount() {
        AccountId bank = ledger.bank(USD);
        AccountId closed = ledger.customer(USD);
        jdbc.sql("UPDATE accounts SET status = 'CLOSED' WHERE id = :id")
                .param("id", closed.value())
                .update();

        assertThatThrownBy(() -> ledger.fund(bank, closed, usd(100))).isInstanceOf(AccountClosedException.class);
        assertThat(entryCount(bank)).isZero();
    }

    @Test
    void rejectsAnEntryInAnotherCurrencyThanItsAccount() {
        AccountId eurBank = ledger.bank(EUR);
        AccountId usdCustomer = ledger.customer(USD);

        assertThatThrownBy(() -> ledger.fund(eurBank, usdCustomer, Money.of(100, EUR)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void recordsTheDescriptionAndTheBusinessDateInUtc() {
        LocalDate before = LocalDate.now(ZoneOffset.UTC);
        LedgerTransactionId txn = ledger.fund(ledger.bank(USD), ledger.customer(USD), usd(1));
        LocalDate after = LocalDate.now(ZoneOffset.UTC);

        String description = jdbc.sql("SELECT description FROM ledger_transactions WHERE id = :id")
                .param("id", txn.value())
                .query(String.class)
                .single();
        LocalDate effectiveDate = jdbc.sql("SELECT effective_date FROM ledger_transactions WHERE id = :id")
                .param("id", txn.value())
                .query(LocalDate.class)
                .single();
        assertThat(description).isEqualTo("test funding");
        assertThat(effectiveDate).isBetween(before, after);
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
