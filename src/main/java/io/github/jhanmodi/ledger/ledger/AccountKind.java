package io.github.jhanmodi.ledger.ledger;

/** Who an account belongs to. See the glossary. */
public enum AccountKind {
    /** Owned by an API client. Caches its balance and can never go negative. */
    CUSTOMER,
    /** Owned by the platform (bank settlement, FX pools, fees). Balance derived from entries; may go negative. */
    SYSTEM
}
