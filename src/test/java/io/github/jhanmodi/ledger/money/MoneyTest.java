package io.github.jhanmodi.ledger.money;

import static io.github.jhanmodi.ledger.money.CurrencyCode.EUR;
import static io.github.jhanmodi.ledger.money.CurrencyCode.JPY;
import static io.github.jhanmodi.ledger.money.CurrencyCode.KWD;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/** Worked examples from the "Money rules" section of docs/design.md (allocation lives in MoneyAllocationTest). */
class MoneyTest {

    private static final BigDecimal TEN_PERCENT = new BigDecimal("0.1");

    @Test
    void currencyIsRequired() {
        assertThatThrownBy(() -> Money.of(100, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void zeroHasNoMinorUnits() {
        assertThat(Money.zero(JPY)).isEqualTo(Money.of(0, JPY));
        assertThat(Money.zero(JPY).isZero()).isTrue();
    }

    @Test
    void addsAndSubtractsInTheSameCurrency() {
        assertThat(usd(1050).plus(usd(75))).isEqualTo(usd(1125));
        assertThat(usd(1050).minus(usd(75))).isEqualTo(usd(975));
        assertThat(usd(75).minus(usd(1050))).isEqualTo(usd(-975));
    }

    @Test
    void refusesToMixCurrencies() {
        Money eur = Money.of(100, EUR);

        assertThatThrownBy(() -> usd(100).plus(eur))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("EUR");
        assertThatThrownBy(() -> usd(100).minus(eur)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> usd(100).compareTo(eur)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void overflowThrowsInsteadOfWrappingAround() {
        assertThatThrownBy(() -> usd(Long.MAX_VALUE).plus(usd(1))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> usd(Long.MIN_VALUE).minus(usd(1))).isInstanceOf(ArithmeticException.class);
        // Long.MIN_VALUE has no positive counterpart in a long.
        assertThatThrownBy(() -> usd(Long.MIN_VALUE).negate()).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void negateFlipsTheSign() {
        assertThat(usd(250).negate()).isEqualTo(usd(-250));
        assertThat(usd(-250).negate()).isEqualTo(usd(250));
    }

    @Test
    void reportsItsSign() {
        assertThat(usd(1).isPositive()).isTrue();
        assertThat(usd(-1).isNegative()).isTrue();
        assertThat(usd(0).isZero()).isTrue();
        assertThat(usd(0).isPositive()).isFalse();
        assertThat(usd(0).isNegative()).isFalse();
    }

    @Test
    void comparesAmountsInTheSameCurrency() {
        assertThat(usd(100).compareTo(usd(200))).isNegative();
        assertThat(usd(200).compareTo(usd(100))).isPositive();
        assertThat(usd(100).compareTo(usd(100))).isZero();
    }

    @Test
    void equalityNeedsTheSameAmountAndCurrency() {
        assertThat(usd(1050)).isEqualTo(usd(1050)).hasSameHashCodeAs(usd(1050));
        assertThat(usd(1050)).isNotEqualTo(Money.of(1050, EUR));
    }

    @Test
    void displaysMajorUnitsUsingTheCurrencyExponent() {
        assertThat(usd(1050)).hasToString("10.50 USD");
        assertThat(Money.of(1000, JPY)).hasToString("1000 JPY");
        assertThat(Money.of(1234, KWD)).hasToString("1.234 KWD");
        assertThat(usd(-5)).hasToString("-0.05 USD");
    }

    @Test
    void multiplyRoundsTiesWithTheNamedMode() {
        // 25 cents x 0.1 = 2.5 cents: a tie, so the rounding mode decides.
        assertThat(usd(25).multiply(TEN_PERCENT, RoundingMode.HALF_EVEN)).isEqualTo(usd(2));
        assertThat(usd(25).multiply(TEN_PERCENT, RoundingMode.HALF_UP)).isEqualTo(usd(3));
        // 35 cents x 0.1 = 3.5 cents: both give 4 (4 is the even neighbour, and half-up rounds up).
        assertThat(usd(35).multiply(TEN_PERCENT, RoundingMode.HALF_EVEN)).isEqualTo(usd(4));
        assertThat(usd(35).multiply(TEN_PERCENT, RoundingMode.HALF_UP)).isEqualTo(usd(4));
        // Java's HALF_UP rounds ties away from zero, so -2.5 becomes -3.
        assertThat(usd(-25).multiply(TEN_PERCENT, RoundingMode.HALF_UP)).isEqualTo(usd(-3));
        assertThat(usd(-25).multiply(TEN_PERCENT, RoundingMode.HALF_EVEN)).isEqualTo(usd(-2));
    }

    @Test
    void multiplyRoundsToWholeMinorUnitsWhateverTheExponent() {
        BigDecimal half = new BigDecimal("0.5");
        // 1001 yen x 0.5 = 500.5 yen; JPY has no unit smaller than the yen.
        assertThat(Money.of(1001, JPY).multiply(half, RoundingMode.HALF_EVEN)).isEqualTo(Money.of(500, JPY));
        // 1.001 KWD x 0.5 = 0.5005 KWD, which rounds to 0.500 KWD (500 fils).
        assertThat(Money.of(1001, KWD).multiply(half, RoundingMode.HALF_EVEN)).isEqualTo(Money.of(500, KWD));
    }

    @Test
    void unnecessaryRoundingModeRejectsInexactResults() {
        assertThat(usd(30).multiply(TEN_PERCENT, RoundingMode.UNNECESSARY)).isEqualTo(usd(3));
        assertThatThrownBy(() -> usd(25).multiply(TEN_PERCENT, RoundingMode.UNNECESSARY))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void multiplyRequiresAFactorAndARoundingMode() {
        assertThatThrownBy(() -> usd(100).multiply(null, RoundingMode.HALF_EVEN))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> usd(100).multiply(TEN_PERCENT, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void multiplyThrowsWhenTheResultDoesNotFitInALong() {
        assertThatThrownBy(() -> usd(Long.MAX_VALUE).multiply(new BigDecimal("2"), RoundingMode.HALF_EVEN))
                .isInstanceOf(ArithmeticException.class);
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
