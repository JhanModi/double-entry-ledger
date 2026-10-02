package io.github.jhanmodi.ledger.money;

/**
 * The currencies the ledger supports, with their ISO 4217 exponent. This must match the {@code currencies} table
 * (V1 migration); {@code CurrencyCodeIT} fails if the two drift apart. Adding a currency means a change here plus a new
 * migration. See ADR-0013.
 */
public enum CurrencyCode {
    USD(2),
    EUR(2),
    JPY(0),
    KWD(3);

    private final int exponent;

    CurrencyCode(int exponent) {
        this.exponent = exponent;
    }

    /** Number of decimal places in the minor unit, e.g. 2 for USD (cents), 0 for JPY, 3 for KWD (fils). */
    public int exponent() {
        return exponent;
    }
}
