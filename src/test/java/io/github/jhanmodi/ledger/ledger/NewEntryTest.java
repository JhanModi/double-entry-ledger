package io.github.jhanmodi.ledger.ledger;

import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NewEntryTest {

    private static final AccountId ACCOUNT = new AccountId(UUID.randomUUID());

    @Test
    void amountsMustBePositive() {
        assertThatThrownBy(() -> NewEntry.debit(ACCOUNT, Money.of(0, USD)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NewEntry.credit(ACCOUNT, Money.of(-1, USD)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factoriesSetTheDirection() {
        assertThat(NewEntry.debit(ACCOUNT, Money.of(1, USD)).direction()).isEqualTo(Direction.DEBIT);
        assertThat(NewEntry.credit(ACCOUNT, Money.of(1, USD)).direction()).isEqualTo(Direction.CREDIT);
    }
}
