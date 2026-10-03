package io.github.jhanmodi.ledger.transfers;

import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;

/**
 * The largest amount one transfer or funding may move, per currency (ADR-0018, D4; worked examples in docs/design.md,
 * "Amount limits"). Each is set to roughly a million US dollars of real value, so "one million" doesn't mean about
 * $7,000 in JPY and about $3 million in KWD.
 *
 * <p>A sanity cap against wrong input (a misplaced decimal point, a confused currency), not a per-client risk limit. It's
 * checked in Java only, because it's policy that may change; the database enforces only that amounts are positive.
 */
final class AmountLimits {

    /** 1,000,000.00 USD. */
    static final long USD_MAX_MINOR_UNITS = 100_000_000L;
    /** 1,000,000.00 EUR. */
    static final long EUR_MAX_MINOR_UNITS = 100_000_000L;
    /** 150,000,000 JPY. The yen has no minor unit, so minor units and yen are the same. */
    static final long JPY_MAX_MINOR_UNITS = 150_000_000L;
    /** 300,000.000 KWD. The dinar has three decimal places (fils). */
    static final long KWD_MAX_MINOR_UNITS = 300_000_000L;

    private AmountLimits() {}

    static Money maximum(CurrencyCode currency) {
        // No default branch: adding a currency to CurrencyCode breaks the build here until it has a limit.
        long minorUnits = switch (currency) {
            case USD -> USD_MAX_MINOR_UNITS;
            case EUR -> EUR_MAX_MINOR_UNITS;
            case JPY -> JPY_MAX_MINOR_UNITS;
            case KWD -> KWD_MAX_MINOR_UNITS;
        };
        return Money.of(minorUnits, currency);
    }

    /** Throws {@link AmountTooLargeException} if the amount is above its currency's maximum. The maximum itself is allowed. */
    static void requireWithinLimit(Money amount) {
        Money maximum = maximum(amount.currency());
        if (amount.compareTo(maximum) > 0) {
            throw new AmountTooLargeException(maximum);
        }
    }
}
