package io.github.jhanmodi.ledger.ledger;

/**
 * What a ledger transaction represents. Must match the {@code ledger_transactions_type_known} constraint; each
 * milestone that introduces a type extends both (as with currencies, ADR-0013).
 */
public enum LedgerTransactionType {
    /** Money moving between accounts inside the ledger. */
    TRANSFER,
    /** Money entering a customer account from outside (simulated deposit; exposed to admins in M4). */
    FUNDING
}
