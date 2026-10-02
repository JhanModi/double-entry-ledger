package io.github.jhanmodi.ledger.ledger;

import static io.github.jhanmodi.ledger.ledger.LedgerTransactionType.TRANSFER;
import static io.github.jhanmodi.ledger.ledger.NewEntry.credit;
import static io.github.jhanmodi.ledger.ledger.NewEntry.debit;
import static io.github.jhanmodi.ledger.money.CurrencyCode.EUR;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The structural checks a posting must pass before it gets anywhere near the database. */
class PostingRequestTest {

    private static final AccountId A = new AccountId(UUID.randomUUID());
    private static final AccountId B = new AccountId(UUID.randomUUID());
    private static final AccountId C = new AccountId(UUID.randomUUID());
    private static final AccountId D = new AccountId(UUID.randomUUID());

    @Test
    void acceptsABalancedPosting() {
        PostingRequest request = new PostingRequest(TRANSFER, "rent", List.of(debit(A, usd(100)), credit(B, usd(100))));

        assertThat(request.entries()).hasSize(2);
    }

    @Test
    void acceptsAPostingBalancedInEachOfSeveralCurrencies() {
        List<NewEntry> entries = List.of(
                debit(A, usd(100)), credit(B, usd(60)), credit(B, usd(40)), debit(C, eur(50)), credit(D, eur(50)));

        assertThat(new PostingRequest(TRANSFER, null, entries).entries()).hasSize(5);
    }

    @Test
    void needsAtLeastTwoEntries() {
        assertThatThrownBy(() -> new PostingRequest(TRANSFER, null, List.of()))
                .isInstanceOf(UnbalancedPostingException.class);
        assertThatThrownBy(() -> new PostingRequest(TRANSFER, null, List.of(debit(A, usd(100)))))
                .isInstanceOf(UnbalancedPostingException.class);
    }

    @Test
    void rejectsDebitsThatDoNotEqualCredits() {
        assertThatThrownBy(() -> new PostingRequest(TRANSFER, null, List.of(debit(A, usd(100)), credit(B, usd(99)))))
                .isInstanceOf(UnbalancedPostingException.class)
                .hasMessageContaining("USD");
    }

    @Test
    void rejectsAPostingThatOnlyBalancesAcrossCurrencies() {
        // 100 in each direction, but in different currencies: that's two unbalanced currencies, not one balanced
        // posting.
        assertThatThrownBy(() -> new PostingRequest(TRANSFER, null, List.of(debit(A, usd(100)), credit(B, eur(100)))))
                .isInstanceOf(UnbalancedPostingException.class);
    }

    @Test
    void descriptionIsOptionalButLimitedTo500Characters() {
        List<NewEntry> entries = List.of(debit(A, usd(1)), credit(B, usd(1)));

        assertThat(new PostingRequest(TRANSFER, null, entries).description()).isNull();
        assertThat(new PostingRequest(TRANSFER, "x".repeat(500), entries).description())
                .hasSize(500);
        assertThatThrownBy(() -> new PostingRequest(TRANSFER, "x".repeat(501), entries))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsCharactersTheWayPostgresDoes() {
        // 500 emoji are 500 characters to Postgres but 1,000 UTF-16 units to String.length().
        String fiveHundredEmoji = "💸".repeat(500);

        assertThat(new PostingRequest(TRANSFER, fiveHundredEmoji, List.of(debit(A, usd(1)), credit(B, usd(1)))))
                .isNotNull();
    }

    @Test
    void keepsItsOwnCopyOfTheEntries() {
        List<NewEntry> entries = new ArrayList<>(List.of(debit(A, usd(1)), credit(B, usd(1))));
        PostingRequest request = new PostingRequest(TRANSFER, null, entries);

        entries.clear();

        assertThat(request.entries()).hasSize(2);
        assertThatThrownBy(() -> request.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresAType() {
        assertThatThrownBy(() -> new PostingRequest(null, null, List.of(debit(A, usd(1)), credit(B, usd(1)))))
                .isInstanceOf(NullPointerException.class);
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }

    private static Money eur(long cents) {
        return Money.of(cents, EUR);
    }
}
