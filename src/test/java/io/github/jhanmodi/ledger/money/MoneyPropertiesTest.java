package io.github.jhanmodi.ledger.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Scale;

/** Properties of Money arithmetic that must hold for every input. jqwik checks each one against 1,000 generated cases. */
class MoneyPropertiesTest {

    // Amounts are bounded to ±10^18 so that adding two of them can't overflow. Overflow itself is tested in MoneyTest.
    private static final long LIMIT = 1_000_000_000_000_000_000L;

    @Property
    void additionIsCommutative(
            @ForAll @LongRange(min = -LIMIT, max = LIMIT) long a,
            @ForAll @LongRange(min = -LIMIT, max = LIMIT) long b,
            @ForAll CurrencyCode currency) {
        Money x = Money.of(a, currency);
        Money y = Money.of(b, currency);

        assertThat(x.plus(y)).isEqualTo(y.plus(x));
    }

    @Property
    void subtractingWhatWasAddedGivesTheOriginal(
            @ForAll @LongRange(min = -LIMIT, max = LIMIT) long a,
            @ForAll @LongRange(min = -LIMIT, max = LIMIT) long b,
            @ForAll CurrencyCode currency) {
        Money x = Money.of(a, currency);
        Money y = Money.of(b, currency);

        assertThat(x.plus(y).minus(y)).isEqualTo(x);
    }

    @Property
    void anAmountPlusItsNegationIsZero(
            @ForAll @LongRange(min = -Long.MAX_VALUE, max = Long.MAX_VALUE) long a, @ForAll CurrencyCode currency) {
        Money x = Money.of(a, currency);

        assertThat(x.plus(x.negate()).isZero()).isTrue();
        assertThat(x.negate().negate()).isEqualTo(x);
    }

    @Property
    void compareToAgreesWithTheMinorUnits(@ForAll long a, @ForAll long b, @ForAll CurrencyCode currency) {
        int comparison = Money.of(a, currency).compareTo(Money.of(b, currency));

        assertThat(Integer.signum(comparison)).isEqualTo(Long.compare(a, b));
    }

    @Property
    void roundingToNearestIsNeverMoreThanHalfAMinorUnitOff(
            @ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long amount,
            @ForAll @BigRange(min = "-10", max = "10") @Scale(6) BigDecimal factor,
            @ForAll("roundToNearestModes") RoundingMode rounding) {
        BigDecimal exact = BigDecimal.valueOf(amount).multiply(factor);
        BigDecimal rounded = BigDecimal.valueOf(
                Money.of(amount, CurrencyCode.USD).multiply(factor, rounding).minorUnits());

        assertThat(rounded.subtract(exact).abs()).isLessThanOrEqualTo(new BigDecimal("0.5"));
    }

    @Property
    void everyRoundingModeIsLessThanOneMinorUnitOff(
            @ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long amount,
            @ForAll @BigRange(min = "-10", max = "10") @Scale(6) BigDecimal factor,
            @ForAll("allModesExceptUnnecessary") RoundingMode rounding) {
        BigDecimal exact = BigDecimal.valueOf(amount).multiply(factor);
        BigDecimal rounded = BigDecimal.valueOf(
                Money.of(amount, CurrencyCode.USD).multiply(factor, rounding).minorUnits());

        assertThat(rounded.subtract(exact).abs()).isLessThan(BigDecimal.ONE);
    }

    @Provide
    Arbitrary<RoundingMode> roundToNearestModes() {
        return Arbitraries.of(RoundingMode.HALF_UP, RoundingMode.HALF_DOWN, RoundingMode.HALF_EVEN);
    }

    @Provide
    Arbitrary<RoundingMode> allModesExceptUnnecessary() {
        return Arbitraries.of(EnumSet.complementOf(EnumSet.of(RoundingMode.UNNECESSARY)));
    }
}
