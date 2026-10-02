package io.github.jhanmodi.ledger.ledger;

import static io.github.jhanmodi.ledger.ledger.NewEntry.credit;
import static io.github.jhanmodi.ledger.ledger.NewEntry.debit;

import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import java.util.List;

/**
 * Shorthand for setting up ledger state in integration tests. Everything goes through the real services. Each test
 * creates its own accounts, because append-only data can't be cleaned up between tests.
 */
final class LedgerFixtures {

    private final AccountService accounts;
    private final PostingService postings;

    LedgerFixtures(AccountService accounts, PostingService postings) {
        this.accounts = accounts;
        this.postings = postings;
    }

    AccountId customer(CurrencyCode currency) {
        return accounts.openCustomerAccount(currency);
    }

    /** A bank-settlement account: an asset, so debits increase it. */
    AccountId bank(CurrencyCode currency) {
        return accounts.openSystemAccount(AccountType.ASSET, currency);
    }

    /** Money arriving from outside: the bank holds more cash, and the platform owes the customer more. */
    LedgerTransactionId fund(AccountId bank, AccountId customer, Money amount) {
        return postings.post(new PostingRequest(
                LedgerTransactionType.FUNDING, "test funding", List.of(debit(bank, amount), credit(customer, amount))));
    }

    LedgerTransactionId transfer(AccountId from, AccountId to, Money amount) {
        return postings.post(new PostingRequest(
                LedgerTransactionType.TRANSFER, null, List.of(debit(from, amount), credit(to, amount))));
    }
}
