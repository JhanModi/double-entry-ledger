package io.github.jhanmodi.ledger.ledger;

import static io.github.jhanmodi.ledger.ledger.NewEntry.credit;
import static io.github.jhanmodi.ledger.ledger.NewEntry.debit;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.money.Money;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BalanceChangesTest {

    // Chosen so that java.util.UUID's signed ordering would put HIGH first; the lock order must follow Postgres.
    private static final AccountId LOW = id("00000000-0000-0000-0000-000000000001");
    private static final AccountId HIGH = id("80000000-0000-0000-0000-000000000000");
    private static final AccountId BANK = id("40000000-0000-0000-0000-000000000000");

    private static final Map<AccountId, Account> ACCOUNTS =
            Map.of(LOW, customer(LOW), HIGH, customer(HIGH), BANK, bank(BANK));

    // --- What changes ---

    @Test
    void aCreditRaisesACustomersBalanceAndADebitLowersIt() {
        // Customer accounts are liabilities, so credit is their normal side (ADR-0003).
        BalanceChanges changes = BalanceChanges.of(List.of(debit(LOW, usd(300)), credit(HIGH, usd(300))), ACCOUNTS);

        assertThat(changes.deltas()).containsExactly(Map.entry(LOW, -300L), Map.entry(HIGH, 300L));
    }

    @Test
    void netsSeveralEntriesOnTheSameAccount() {
        BalanceChanges changes =
                BalanceChanges.of(List.of(credit(LOW, usd(100)), debit(LOW, usd(30)), debit(BANK, usd(70))), ACCOUNTS);

        assertThat(changes.deltas()).containsExactly(Map.entry(LOW, 70L));
    }

    @Test
    void leavesOutSystemAccounts() {
        BalanceChanges changes = BalanceChanges.of(List.of(debit(BANK, usd(500)), credit(LOW, usd(500))), ACCOUNTS);

        assertThat(changes.deltas()).containsOnlyKeys(LOW);
        assertThat(changes.accountsToLock()).containsExactly(LOW);
    }

    // --- What gets locked ---

    @Test
    void locksInAscendingIdOrderTheWayPostgresSortsWhateverTheEntriesOrder() {
        BalanceChanges changes = BalanceChanges.of(List.of(debit(HIGH, usd(1)), credit(LOW, usd(1))), ACCOUNTS);

        assertThat(changes.accountsToLock()).containsExactly(LOW, HIGH);
    }

    @Test
    void locksAnAccountWhoseEntriesCancelOutBecauseEntriesAreStillWrittenToIt() {
        BalanceChanges changes = BalanceChanges.of(
                List.of(credit(LOW, usd(50)), debit(LOW, usd(50)), credit(BANK, usd(1)), debit(BANK, usd(1))),
                ACCOUNTS);

        assertThat(changes.accountsToLock()).containsExactly(LOW);
        assertThat(changes.deltas()).containsExactly(Map.entry(LOW, 0L));
    }

    // --- Whether it can be posted ---

    @Test
    void spendingExactlyTheAvailableBalanceIsAllowed() {
        BalanceChanges changes = transfer(LOW, HIGH, 1000);

        assertThatCode(() -> changes.requirePostable(List.of(open(LOW, 1000, 0), open(HIGH, 0, 0))))
                .doesNotThrowAnyException();
    }

    @Test
    void spendingOneMinorUnitMoreThanAvailableIsInsufficientFundsNamingTheAccount() {
        BalanceChanges changes = transfer(LOW, HIGH, 1001);

        assertThatThrownBy(() -> changes.requirePostable(List.of(open(LOW, 1000, 0), open(HIGH, 0, 0))))
                .isInstanceOfSatisfying(
                        InsufficientFundsException.class,
                        e -> assertThat(e.accountId()).isEqualTo(LOW));
    }

    @Test
    void heldFundsAreNotAvailable() {
        BalanceChanges changes = transfer(LOW, HIGH, 800);

        // Posted 1000, of which 300 is held: 700 available.
        assertThatThrownBy(() -> changes.requirePostable(List.of(open(LOW, 1000, 300), open(HIGH, 0, 0))))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void aClosedAccountIsRejectedEvenWhenItWouldOnlyReceive() {
        BalanceChanges changes = transfer(LOW, HIGH, 100);

        assertThatThrownBy(() -> changes.requirePostable(List.of(open(LOW, 1000, 0), closed(HIGH))))
                .isInstanceOfSatisfying(
                        AccountClosedException.class,
                        e -> assertThat(e.accountId()).isEqualTo(HIGH));
    }

    @Test
    void aClosedAccountIsReportedAsClosedEvenIfItIsAlsoShortOfFunds() {
        // HIGH would go to -100, and it's closed.
        BalanceChanges changes = transfer(HIGH, LOW, 100);

        assertThatThrownBy(() -> changes.requirePostable(List.of(open(LOW, 0, 0), closed(HIGH))))
                .isInstanceOf(AccountClosedException.class);
    }

    @Test
    void anAccountThatWasNotLockedIsAProgrammingError() {
        BalanceChanges changes = transfer(LOW, HIGH, 100);

        assertThatThrownBy(() -> changes.requirePostable(List.of(open(LOW, 1000, 0))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(HIGH.toString());
    }

    @Test
    void aBalanceThatWouldOverflowIsAnErrorNotAWrapAround() {
        BalanceChanges changes = transfer(LOW, HIGH, 1);

        assertThatThrownBy(() -> changes.requirePostable(List.of(open(LOW, 1, 0), open(HIGH, Long.MAX_VALUE, 0))))
                .isInstanceOf(ArithmeticException.class);
    }

    // --- helpers ---

    private static BalanceChanges transfer(AccountId from, AccountId to, long cents) {
        return BalanceChanges.of(List.of(debit(from, usd(cents)), credit(to, usd(cents))), ACCOUNTS);
    }

    private static LockedAccount open(AccountId id, long posted, long held) {
        return new LockedAccount(id, AccountStatus.OPEN, posted, held);
    }

    private static LockedAccount closed(AccountId id) {
        return new LockedAccount(id, AccountStatus.CLOSED, 0, 0);
    }

    static Account customer(AccountId id) {
        return new Account(
                id,
                AccountKind.CUSTOMER,
                AccountType.LIABILITY,
                USD,
                AccountStatus.OPEN,
                new ClientId(UUID.fromString("00000000-0000-7000-8000-00000000c1e7")));
    }

    static Account bank(AccountId id) {
        return new Account(id, AccountKind.SYSTEM, AccountType.ASSET, USD, AccountStatus.OPEN, null);
    }

    private static AccountId id(String uuid) {
        return new AccountId(UUID.fromString(uuid));
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
