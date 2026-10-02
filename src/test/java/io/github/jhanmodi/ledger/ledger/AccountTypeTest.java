package io.github.jhanmodi.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountTypeTest {

    @Test
    void assetsAndExpensesIncreaseWithDebits() {
        assertThat(AccountType.ASSET.normalSide()).isEqualTo(Direction.DEBIT);
        assertThat(AccountType.EXPENSE.normalSide()).isEqualTo(Direction.DEBIT);
    }

    @Test
    void liabilitiesEquityAndRevenueIncreaseWithCredits() {
        assertThat(AccountType.LIABILITY.normalSide()).isEqualTo(Direction.CREDIT);
        assertThat(AccountType.EQUITY.normalSide()).isEqualTo(Direction.CREDIT);
        assertThat(AccountType.REVENUE.normalSide()).isEqualTo(Direction.CREDIT);
    }
}
