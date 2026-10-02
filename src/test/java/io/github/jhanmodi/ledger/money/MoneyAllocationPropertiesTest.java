package io.github.jhanmodi.ledger.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * Rules {@link Money#allocate(long...)} must follow for every input. These fail until allocate() is implemented.
 * Amounts cover the whole range from 0 to Long.MAX_VALUE.
 */
class MoneyAllocationPropertiesTest {

    @Property
    void partsAlwaysAddUpToTheOriginalAmount(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long amount,
            @ForAll("ratios") long[] ratios,
            @ForAll CurrencyCode currency) {
        List<Money> parts = Money.of(amount, currency).allocate(ratios);

        BigInteger sum = parts.stream()
                .map(part -> BigInteger.valueOf(part.minorUnits()))
                .reduce(BigInteger.ZERO, BigInteger::add);
        assertThat(sum).isEqualTo(BigInteger.valueOf(amount));
    }

    @Property
    void returnsOnePartPerRatioAllInTheSameCurrency(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long amount,
            @ForAll("ratios") long[] ratios,
            @ForAll CurrencyCode currency) {
        List<Money> parts = Money.of(amount, currency).allocate(ratios);

        assertThat(parts).hasSize(ratios.length);
        assertThat(parts).allSatisfy(part -> assertThat(part.currency()).isEqualTo(currency));
    }

    @Property
    void eachPartIsWithinOneMinorUnitOfItsExactShare(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long amount, @ForAll("ratios") long[] ratios) {
        List<Money> parts = Money.of(amount, CurrencyCode.USD).allocate(ratios);

        // The exact share is amount × ratio ÷ total. "part is within 1 of it" is the same as
        // |part × total − amount × ratio| < total, which stays in whole numbers.
        BigInteger total = BigInteger.valueOf(Arrays.stream(ratios).sum());
        for (int i = 0; i < ratios.length; i++) {
            BigInteger part = BigInteger.valueOf(parts.get(i).minorUnits());
            BigInteger exactTimesTotal = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(ratios[i]));
            assertThat(part.signum()).as("part %d is negative", i).isGreaterThanOrEqualTo(0);
            assertThat(part.multiply(total).subtract(exactTimesTotal).abs())
                    .as("part %d is more than one minor unit away from its exact share", i)
                    .isLessThan(total);
        }
    }

    @Property
    void aZeroRatioAlwaysGetsZero(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long amount, @ForAll("ratios") long[] ratios) {
        List<Money> parts = Money.of(amount, CurrencyCode.USD).allocate(ratios);

        for (int i = 0; i < ratios.length; i++) {
            if (ratios[i] == 0) {
                assertThat(parts.get(i).isZero())
                        .as("part %d has a zero ratio", i)
                        .isTrue();
            }
        }
    }

    @Property
    void aLargerRatioNeverGetsASmallerPart(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long amount, @ForAll("ratios") long[] ratios) {
        List<Money> parts = Money.of(amount, CurrencyCode.USD).allocate(ratios);

        for (int i = 0; i < ratios.length; i++) {
            for (int j = 0; j < ratios.length; j++) {
                if (ratios[i] > ratios[j]) {
                    assertThat(parts.get(i).minorUnits())
                            .as("part %d (ratio %d) vs part %d (ratio %d)", i, ratios[i], j, ratios[j])
                            .isGreaterThanOrEqualTo(parts.get(j).minorUnits());
                }
            }
        }
    }

    @Property
    void theSameInputAlwaysGivesTheSameParts(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long amount, @ForAll("ratios") long[] ratios) {
        Money money = Money.of(amount, CurrencyCode.USD);

        assertThat(money.allocate(ratios)).isEqualTo(money.allocate(ratios));
    }

    /** 1 to 10 ratios between 0 and 10^12, at least one of them positive. */
    @Provide
    Arbitrary<long[]> ratios() {
        return Arbitraries.longs()
                .between(0, 1_000_000_000_000L)
                .array(long[].class)
                .ofMinSize(1)
                .ofMaxSize(10)
                .filter(ratios -> Arrays.stream(ratios).anyMatch(ratio -> ratio > 0));
    }
}
