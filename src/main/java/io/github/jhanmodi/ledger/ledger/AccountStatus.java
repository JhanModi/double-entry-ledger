package io.github.jhanmodi.ledger.ledger;

/** Whether an account accepts new postings. Accounts are closed, never deleted. */
public enum AccountStatus {
    OPEN,
    CLOSED
}
