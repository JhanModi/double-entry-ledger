package io.github.jhanmodi.ledger.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The worked examples in docs/design.md, "Amount limits". */
class AmountLimitsTest {

    @ParameterizedTest(name = "{0}: {1} minor units allowed, {2} rejected")
    @CsvSource({
        // currency, the maximum (allowed), one minor unit more (rejected)
        "USD, 100000000, 100000001", // 1,000,000.00 USD and 1,000,000.01 USD
        "EUR, 100000000, 100000001", // 1,000,000.00 EUR and 1,000,000.01 EUR
        "JPY, 150000000, 150000001", // ¥150,000,000 and ¥150,000,001
        "KWD, 300000000, 300000001", // 300,000.000 KWD and 300,000.001 KWD
    })
    void theMaximumIsAllowedAndOneMinorUnitMoreIsNot(CurrencyCode currency, long maximum, long oneMore) {
        assertThatCode(() -> AmountLimits.requireWithinLimit(Money.of(maximum, currency)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AmountLimits.requireWithinLimit(Money.of(oneMore, currency)))
                .isInstanceOf(AmountTooLargeException.class)
                .satisfies(e ->
                        assertThat(((AmountTooLargeException) e).maximum()).isEqualTo(Money.of(maximum, currency)));
    }

    @Test
    void theMaximumsReadAsTheSpecSaysInEachCurrencysOwnUnits() {
        assertThat(AmountLimits.maximum(CurrencyCode.USD)).hasToString("1000000.00 USD");
        assertThat(AmountLimits.maximum(CurrencyCode.EUR)).hasToString("1000000.00 EUR");
        assertThat(AmountLimits.maximum(CurrencyCode.JPY)).hasToString("150000000 JPY");
        assertThat(AmountLimits.maximum(CurrencyCode.KWD)).hasToString("300000.000 KWD");
    }

    @ParameterizedTest
    @EnumSource(CurrencyCode.class)
    void everyCurrencyHasAPositiveMaximumInItsOwnCurrency(CurrencyCode currency) {
        Money maximum = AmountLimits.maximum(currency);

        assertThat(maximum.currency()).isEqualTo(currency);
        assertThat(maximum.isPositive()).isTrue();
    }

    @Test
    void theMessageStatesTheMaximumSoTheClientKnowsWhatIsAllowed() {
        assertThatThrownBy(() -> AmountLimits.requireWithinLimit(Money.of(Long.MAX_VALUE, CurrencyCode.KWD)))
                .hasMessage("the most one request may move in KWD is 300000.000 KWD");
    }
}
