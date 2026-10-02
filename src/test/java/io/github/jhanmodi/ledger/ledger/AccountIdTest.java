package io.github.jhanmodi.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountIdTest {

    @Test
    void ordersIdsAsUnsignedBytesLikePostgres() {
        AccountId low = id("00000000-0000-0000-0000-000000000001");
        AccountId high = id("80000000-0000-0000-0000-000000000000");

        assertThat(high.compareTo(low)).isPositive();
        assertThat(low.compareTo(high)).isNegative();
        // UUID's own compareTo gets this pair backwards, which is why AccountId doesn't use it.
        assertThat(high.value().compareTo(low.value())).isNegative();
    }

    @Test
    void comparesTheSecondHalfWhenTheFirstHalvesAreEqual() {
        AccountId low = id("00000000-0000-0000-0000-000000000001");
        AccountId high = id("00000000-0000-0000-8000-000000000000");

        assertThat(high.compareTo(low)).isPositive();
    }

    @Test
    void equalIdsCompareAsEqual() {
        assertThat(id("0190f0a8-0000-7000-8000-000000000000").compareTo(id("0190f0a8-0000-7000-8000-000000000000")))
                .isZero();
    }

    @Test
    void requiresAValue() {
        assertThatThrownBy(() -> new AccountId(null)).isInstanceOf(NullPointerException.class);
    }

    private static AccountId id(String uuid) {
        return new AccountId(UUID.fromString(uuid));
    }
}
