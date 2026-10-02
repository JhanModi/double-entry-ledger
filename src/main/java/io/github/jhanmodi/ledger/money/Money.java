package io.github.jhanmodi.ledger.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An exact amount of money: a whole number of minor units (e.g. cents) in one currency. Immutable. See ADR-0002 and
 * the "Money rules" section of docs/design.md.
 *
 * <p>Arithmetic is exact. Overflow throws {@link ArithmeticException} instead of silently wrapping around, and
 * combining two currencies throws {@link CurrencyMismatchException}. Amounts may be negative (balance changes and
 * system-account balances can be); the rule that entry amounts are positive belongs to entries, not to this type.
 */
public record Money(long minorUnits, CurrencyCode currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(long minorUnits, CurrencyCode currency) {
        return new Money(minorUnits, currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    /** Orders amounts of the same currency. Comparing different currencies throws, because there's no right answer. */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    /**
     * Multiplies by an exact decimal factor (e.g. a fee rate) and rounds the result to a whole minor unit.
     *
     * <p>This is the only method on {@code Money} that rounds, and the caller must name the rounding mode, so every
     * rounding point in the codebase is explicit. {@link RoundingMode#UNNECESSARY} throws if the result isn't already
     * a whole minor unit.
     */
    public Money multiply(BigDecimal factor, RoundingMode rounding) {
        Objects.requireNonNull(factor, "factor");
        Objects.requireNonNull(rounding, "rounding");
        BigDecimal exact = BigDecimal.valueOf(minorUnits).multiply(factor);
        return new Money(exact.setScale(0, rounding).longValueExact(), currency);
    }

    /**
     * Splits this amount into parts proportional to {@code ratios}, without losing or creating a single minor unit.
     *
     * <p>Contract (worked examples in docs/design.md, "Money rules"):
     *
     * <ul>
     *   <li>Returns one part per ratio, in the same order, all in this currency.
     *   <li>Each part starts as its exact share ({@code amount × ratio ÷ sum of ratios}) rounded down to a whole minor
     *       unit. The minor units left over are then handed out one at a time to the parts whose exact share lost the
     *       most to that rounding down (the largest remainder). Ties go to the earlier part.
     *   <li>The parts always add up to exactly this amount.
     *   <li>Works for any amount from 0 up to {@link Long#MAX_VALUE}.
     *   <li>Throws {@link IllegalArgumentException} if there are no ratios, any ratio is negative, every ratio is zero,
     *       or this amount is negative.
     *   <li>Throws {@link ArithmeticException} if the ratios add up to more than {@link Long#MAX_VALUE}.
     * </ul>
     *
     * <p>Example: $100.00 in ratios 1:1:1 gives $33.34, $33.33, $33.33.
     */
    public List<Money> allocate(long... ratios) {
        if (ratios.length == 0) {
            throw new IllegalArgumentException("at least one ratio is required");
        }
        if (minorUnits < 0) {
            throw new IllegalArgumentException("cannot allocate a negative amount: " + this);
        }

        long total = 0;
        for (long ratio : ratios) {
            if (ratio < 0) {
                throw new IllegalArgumentException("ratios must not be negative");
            }
            // addExact throws ArithmeticException if the ratios add up to more than a long can hold
            total = Math.addExact(total, ratio);
        }
        if (total == 0) {
            throw new IllegalArgumentException("at least one ratio must be positive");
        }

        long[] base = new long[ratios.length];
        long[] remainder = new long[ratios.length];
        for (int i = 0; i < ratios.length; i++) {
            // BigInteger, because amount × ratio can exceed the largest long
            BigInteger exact = BigInteger.valueOf(minorUnits).multiply(BigInteger.valueOf(ratios[i]));
            BigInteger[] quotientAndRemainder = exact.divideAndRemainder(BigInteger.valueOf(total));
            base[i] = quotientAndRemainder[0].longValueExact();
            remainder[i] = quotientAndRemainder[1].longValueExact();
        }

        long leftover = minorUnits;
        for (long b : base) {
            leftover -= b;
        }

        boolean[] gotExtraUnit = new boolean[ratios.length];
        for (long k = 0; k < leftover; k++) {
            int best = -1;
            for (int i = 0; i < ratios.length; i++) {
                // strict > means an equal remainder never replaces an earlier part
                if (!gotExtraUnit[i] && (best == -1 || remainder[i] > remainder[best])) {
                    best = i;
                }
            }
            // give the winner one unit, and make sure it can't win again
            base[best] += 1;
            gotExtraUnit[best] = true;
        }

        List<Money> parts = new ArrayList<>();
        for (long b : base) {
            parts.add(new Money(b, currency));
        }
        return List.copyOf(parts);
    }

    /** Human-readable form for logs and test failures, e.g. {@code 10.50 USD}. Not meant to be parsed. */
    @Override
    public String toString() {
        return BigDecimal.valueOf(minorUnits, currency.exponent()).toPlainString() + " " + currency;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (other.currency != currency) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
