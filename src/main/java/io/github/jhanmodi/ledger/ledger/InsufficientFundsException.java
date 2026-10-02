package io.github.jhanmodi.ledger.ledger;

/** The posting would take a customer account's available balance below zero. Nothing was posted. */
public final class InsufficientFundsException extends RuntimeException {

    private final AccountId accountId;

    public InsufficientFundsException(AccountId accountId) {
        super("insufficient funds in account " + accountId);
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
