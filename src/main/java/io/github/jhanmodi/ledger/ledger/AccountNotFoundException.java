package io.github.jhanmodi.ledger.ledger;

/** The account doesn't exist. */
public final class AccountNotFoundException extends RuntimeException {

    private final AccountId accountId;

    public AccountNotFoundException(AccountId accountId) {
        super("account " + accountId + " does not exist");
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
