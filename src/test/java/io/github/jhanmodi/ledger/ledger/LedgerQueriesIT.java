package io.github.jhanmodi.ledger.ledger;

import static io.github.jhanmodi.ledger.ledger.NewEntry.credit;
import static io.github.jhanmodi.ledger.ledger.NewEntry.debit;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerQueriesIT {

    @Autowired
    LedgerQueries queries;

    @Autowired
    AccountService accountService;

    @Autowired
    PostingService postingService;

    @Autowired
    ClientService clientService;

    LedgerFixtures ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerFixtures(accountService, postingService, clientService);
    }

    @Test
    void aCustomerBalanceComesFromTheCachedColumns() {
        AccountId customer = ledger.customer(USD);
        ledger.fund(ledger.bank(USD), customer, usd(1000));

        AccountBalance balance = queries.balance(customer);

        assertThat(balance.posted()).isEqualTo(usd(1000));
        assertThat(balance.held()).isEqualTo(usd(0));
        assertThat(balance.available()).isEqualTo(usd(1000));
    }

    @Test
    void aSystemBalanceIsDerivedFromItsEntriesUsingItsNormalSide() {
        AccountId bank = ledger.bank(USD);
        AccountId customer = ledger.customer(USD);
        AccountId fees = accountService.openSystemAccount(AccountType.REVENUE, USD);
        ledger.fund(bank, customer, usd(300));
        ledger.fund(bank, customer, usd(200));

        // A $0.50 fee: the customer owes less (debit), and fee income grows (credit, since revenue is credit-normal).
        postingService.post(new PostingRequest(
                LedgerTransactionType.TRANSFER, "fee", List.of(debit(customer, usd(50)), credit(fees, usd(50)))));

        assertThat(queries.balance(bank).posted()).isEqualTo(usd(500));
        assertThat(queries.balance(fees).posted()).isEqualTo(usd(50));
        assertThat(queries.balance(fees).held()).isEqualTo(usd(0));
        assertThat(queries.balance(customer).posted()).isEqualTo(usd(450));
    }

    @Test
    void aNewAccountHasAZeroBalance() {
        assertThat(queries.balance(ledger.customer(USD)).posted()).isEqualTo(usd(0));
        assertThat(queries.balance(ledger.bank(USD)).posted()).isEqualTo(usd(0));
    }

    @Test
    void historyIsNewestFirstAndPagesWithoutGapsOrDuplicates() {
        AccountId bank = ledger.bank(USD);
        AccountId customer = ledger.customer(USD);
        List<LedgerTransactionId> postedInOrder = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            postedInOrder.add(ledger.fund(bank, customer, usd(i)));
        }

        List<EntryPage> pages = new ArrayList<>();
        Optional<EntryId> cursor = Optional.empty();
        do {
            EntryPage page = queries.history(customer, cursor, 3);
            pages.add(page);
            cursor = page.nextCursor();
        } while (cursor.isPresent());

        assertThat(pages).extracting(page -> page.entries().size()).containsExactly(3, 3, 1);
        List<LedgerTransactionId> seen = pages.stream()
                .flatMap(page -> page.entries().stream())
                .map(EntryView::transactionId)
                .toList();
        assertThat(seen).isEqualTo(postedInOrder.reversed());
    }

    @Test
    void historyShowsTheTransactionBehindEachEntry() {
        AccountId customer = ledger.customer(USD);
        LedgerTransactionId txn = ledger.fund(ledger.bank(USD), customer, usd(1234));

        EntryView entry =
                queries.history(customer, Optional.empty(), 10).entries().getFirst();

        assertThat(entry.transactionId()).isEqualTo(txn);
        assertThat(entry.type()).isEqualTo(LedgerTransactionType.FUNDING);
        assertThat(entry.description()).isEqualTo("test funding");
        assertThat(entry.direction()).isEqualTo(Direction.CREDIT);
        assertThat(entry.amount()).isEqualTo(usd(1234));
        assertThat(entry.effectiveDate()).isNotNull();
        assertThat(entry.recordedAt()).isNotNull();
    }

    @Test
    void anAccountWithNoEntriesHasAnEmptyHistory() {
        EntryPage page = queries.history(ledger.customer(USD), Optional.empty(), 10);

        assertThat(page.entries()).isEmpty();
        assertThat(page.nextCursor()).isEmpty();
    }

    @Test
    void thePageSizeMustBeBetween1And100() {
        AccountId customer = ledger.customer(USD);

        assertThatThrownBy(() -> queries.history(customer, Optional.empty(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queries.history(customer, Optional.empty(), 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAccountIsFoundOnlyByTheClientThatOwnsIt() {
        AccountId account = ledger.customer(USD);
        ClientId stranger = clientService.createClient("stranger");

        assertThat(queries.accountOwnedBy(ledger.client(), account).id()).isEqualTo(account);
        assertThatThrownBy(() -> queries.accountOwnedBy(stranger, account))
                .isInstanceOf(AccountNotFoundException.class);
        // System accounts belong to no client, so no client can look one up.
        assertThatThrownBy(() -> queries.accountOwnedBy(ledger.client(), ledger.bank(USD)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void unknownAccountsAreReportedAsNotFound() {
        AccountId unknown = new AccountId(UUID.randomUUID());

        assertThatThrownBy(() -> queries.balance(unknown)).isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> queries.history(unknown, Optional.empty(), 10))
                .isInstanceOf(AccountNotFoundException.class);
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
