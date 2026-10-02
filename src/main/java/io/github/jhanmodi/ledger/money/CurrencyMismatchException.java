package io.github.jhanmodi.ledger.money;

/**
 * Thrown when an operation combines amounts in different currencies. This is always a programming error: converting
 * between currencies has to be an explicit FX step (M12), never an accident.
 */
public final class CurrencyMismatchException extends IllegalArgumentException {

    public CurrencyMismatchException(CurrencyCode expected, CurrencyCode actual) {
        super("Currency mismatch: expected " + expected + " but got " + actual);
    }
}
