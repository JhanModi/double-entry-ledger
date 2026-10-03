package io.github.jhanmodi.ledger.ledger;

/**
 * The job of a system account that code needs to find, matching {@code accounts.purpose} (V5, ADR-0018). There's at
 * most one account per purpose and currency, and code finds it by these two, never by a hard-coded id.
 */
public enum AccountPurpose {
    /** The cash the platform holds at its bank, in one currency: an asset. Money from outside arrives through it. */
    BANK_SETTLEMENT
}
