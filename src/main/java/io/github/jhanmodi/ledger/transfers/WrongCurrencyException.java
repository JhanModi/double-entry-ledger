package io.github.jhanmodi.ledger.transfers;

import io.github.jhanmodi.ledger.money.CurrencyCode;

/**
 * The amount isn't in the account's currency. Money moves within one currency; converting is an explicit FX step
 * (M12). Nothing was moved.
 *
 * <p>Separate from {@link io.github.jhanmodi.ledger.money.CurrencyMismatchException} on purpose. That one means a
 * programming error (code combined two currencies) and must stay a 500; this one means the client asked for something
 * that can't be done, which is a 422.
 */
public final class WrongCurrencyException extends RuntimeException {

    private final CurrencyCode accountCurrency;
    private final CurrencyCode amountCurrency;

    WrongCurrencyException(CurrencyCode accountCurrency, CurrencyCode amountCurrency) {
        super("the amount is in " + amountCurrency + ", but the account is in " + accountCurrency);
        this.accountCurrency = accountCurrency;
        this.amountCurrency = amountCurrency;
    }

    public CurrencyCode accountCurrency() {
        return accountCurrency;
    }

    public CurrencyCode amountCurrency() {
        return amountCurrency;
    }
}
