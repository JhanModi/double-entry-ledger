package io.github.jhanmodi.ledger.ledger;

/** The account is closed and accepts no new postings. */
public final class AccountClosedException extends RuntimeException {

    private final AccountId accountId;

    public AccountClosedException(AccountId accountId) {
        super("account " + accountId + " is closed");
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
