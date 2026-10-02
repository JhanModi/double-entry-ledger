package io.github.jhanmodi.ledger.money;

import static io.github.jhanmodi.ledger.money.CurrencyCode.JPY;
import static io.github.jhanmodi.ledger.money.CurrencyCode.KWD;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Worked examples for {@link Money#allocate(long...)}, from the "Money rules" section of docs/design.md. These fail
 * until allocate() is implemented (the owner's M2 exercise). MoneyAllocationPropertiesTest checks the general rules.
 */
class MoneyAllocationTest {

    @Test
    void splitsInThirdsAndBreaksTheTieInFavourOfTheEarlierPart() {
        // $100.00 in 1:1:1. Exact shares are 3333.33 cents each; rounded down that's 9999, so one cent is left over.
        // All three lost the same amount (0.33), so the tie goes to the first part.
        assertThat(usd(10_000).allocate(1, 1, 1)).containsExactly(usd(3_334), usd(3_333), usd(3_333));
    }

    @Test
    void splitsFiveCentsInTwo() {
        assertThat(usd(5).allocate(1, 1)).containsExactly(usd(3), usd(2));
    }

    @Test
    void givesTheLeftoverCentToThePartThatLostTheMostToRoundingDown() {
        // $0.01 in 1:99. Exact shares are 0.01 and 0.99 cents; both round down to 0.
        // The second part lost 0.99, the first only 0.01, so the second part gets the cent.
        assertThat(usd(1).allocate(1, 99)).containsExactly(usd(0), usd(1));
    }

    @Test
    void largestRemainderIsNotTheSameAsLeftoverToTheFirstPart() {
        // $1.00 in 1:2:4. Exact shares are 14.29, 28.57 and 57.14 cents. Rounded down: 14 + 28 + 57 = 99.
        // The second part lost the most (0.57), so it gets the leftover cent.
        // (Giving leftovers to the first part instead would produce 15, 28, 57.)
        assertThat(usd(100).allocate(1, 2, 4)).containsExactly(usd(14), usd(29), usd(57));
    }

    @Test
    void handsOutSeveralLeftoverUnitsOneAtATime() {
        // 5 cents in 1:1:1. Exact shares are 1.67 each; rounded down 1 + 1 + 1 = 3, so two cents are left over.
        // All tied, so they go to the first two parts.
        assertThat(usd(5).allocate(1, 1, 1)).containsExactly(usd(2), usd(2), usd(1));
    }

    @Test
    void aZeroRatioGetsNothing() {
        assertThat(Money.of(100, JPY).allocate(1, 0, 1))
                .containsExactly(Money.of(50, JPY), Money.of(0, JPY), Money.of(50, JPY));
    }

    @Test
    void keepsTheCurrencyAndWorksInItsMinorUnits() {
        // 1.000 KWD is 1000 fils (KWD has 3 decimal places).
        assertThat(Money.of(1000, KWD).allocate(1, 1, 1))
                .containsExactly(Money.of(334, KWD), Money.of(333, KWD), Money.of(333, KWD));
    }

    @Test
    void exactSharesNeedNoAdjustment() {
        assertThat(usd(1000).allocate(3, 7)).containsExactly(usd(300), usd(700));
    }

    @Test
    void aSingleRatioGetsEverything() {
        assertThat(usd(1234).allocate(5)).containsExactly(usd(1234));
    }

    @Test
    void zeroSplitsIntoZeros() {
        assertThat(usd(0).allocate(1, 2)).containsExactly(usd(0), usd(0));
    }

    @Test
    void worksForTheLargestPossibleAmount() {
        // Long.MAX_VALUE (9223372036854775807) in 2:1.
        // Exact shares: 6148914691236517204.67 and 3074457345618258602.33. The first part lost more, so it gets the
        // unit.
        assertThat(usd(Long.MAX_VALUE).allocate(2, 1))
                .containsExactly(usd(6_148_914_691_236_517_205L), usd(3_074_457_345_618_258_602L));
    }

    @Test
    void requiresAtLeastOneRatio() {
        assertThatThrownBy(() -> usd(100).allocate()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeRatios() {
        assertThatThrownBy(() -> usd(100).allocate(1, -1, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAtLeastOnePositiveRatio() {
        assertThatThrownBy(() -> usd(100).allocate(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> usd(-100).allocate(1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsWhenTheRatiosAddUpToMoreThanALongCanHold() {
        assertThatThrownBy(() -> usd(100).allocate(Long.MAX_VALUE, 1)).isInstanceOf(ArithmeticException.class);
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
