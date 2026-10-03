package io.github.jhanmodi.ledger.ledger;

import static io.github.jhanmodi.ledger.ledger.NewEntry.credit;
import static io.github.jhanmodi.ledger.ledger.NewEntry.debit;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.money.Money;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Rules {@link BalanceChanges} must follow for every posting: random transfers among three customers and a bank, against
 * random balances. The expected results come from a simple tally written here, independently of the class under test.
 */
class BalanceChangesPropertiesTest {

    private static final AccountId A = new AccountId(UUID.fromString("0190f0a8-0000-7000-8000-00000000000a"));
    private static final AccountId B = new AccountId(UUID.fromString("0190f0a8-0000-7000-8000-00000000000b"));
    private static final AccountId C = new AccountId(UUID.fromString("f190f0a8-0000-7000-8000-00000000000c"));
    private static final AccountId BANK = new AccountId(UUID.fromString("0190f0a8-0000-7000-8000-0000000000ba"));

    private static final Map<AccountId, Account> ACCOUNTS = Map.of(
            A, BalanceChangesTest.customer(A),
            B, BalanceChangesTest.customer(B),
            C, BalanceChangesTest.customer(C),
            BANK, BalanceChangesTest.bank(BANK));

    @Property
    void eachCustomerChangesByItsCreditsMinusItsDebits(@ForAll("postings") List<NewEntry> entries) {
        BalanceChanges changes = BalanceChanges.of(entries, ACCOUNTS);

        assertThat(changes.deltas()).isEqualTo(tally(entries));
        assertThat(changes.accountsToLock())
                .containsExactlyElementsOf(
                        tally(entries).keySet().stream().sorted().toList());
    }

    @Property
    void acceptsAPostingIfAndOnlyIfNoAccountWouldEndBelowZero(
            @ForAll("postings") List<NewEntry> entries, @ForAll("balances") List<LockedAccount> balances) {
        BalanceChanges changes = BalanceChanges.of(entries, ACCOUNTS);
        Map<AccountId, LockedAccount> byId =
                balances.stream().collect(Collectors.toMap(LockedAccount::id, Function.identity()));
        List<LockedAccount> locked =
                changes.accountsToLock().stream().map(byId::get).toList();
        Map<AccountId, Long> availableAfter = new HashMap<>();
        tally(entries)
                .forEach((id, change) -> availableAfter.put(
                        id, byId.get(id).postedBalance() + change - byId.get(id).heldBalance()));

        if (availableAfter.values().stream().anyMatch(available -> available < 0)) {
            assertThatThrownBy(() -> changes.requirePostable(locked))
                    .isInstanceOfSatisfying(
                            InsufficientFundsException.class,
                            e -> assertThat(availableAfter.get(e.accountId()))
                                    .as("the account named really would go below zero")
                                    .isNegative());
        } else {
            assertThatCode(() -> changes.requirePostable(locked)).doesNotThrowAnyException();
        }
    }

    /** 1 to 4 transfers between any two of the accounts (possibly the same one), as one posting. */
    @Provide
    Arbitrary<List<NewEntry>> postings() {
        Arbitrary<AccountId> account = Arbitraries.of(A, B, C, BANK);
        Arbitrary<List<NewEntry>> transfer = Combinators.combine(
                        account, account, Arbitraries.longs().between(1, 20_000))
                .as((from, to, cents) -> List.of(debit(from, usd(cents)), credit(to, usd(cents))));
        return transfer.list()
                .ofMinSize(1)
                .ofMaxSize(4)
                .map(transfers -> transfers.stream().flatMap(List::stream).toList());
    }

    /** A balance for each customer: posted between 0 and 20,000, of which up to all is held. */
    @Provide
    Arbitrary<List<LockedAccount>> balances() {
        return Combinators.combine(balance(A), balance(B), balance(C)).as(List::of);
    }

    private static Arbitrary<LockedAccount> balance(AccountId id) {
        return Arbitraries.longs()
                .between(0, 20_000)
                .flatMap(posted -> Arbitraries.longs()
                        .between(0, posted)
                        .map(held -> new LockedAccount(id, AccountStatus.OPEN, posted, held)));
    }

    /** Customers are liabilities: a credit adds to the balance, a debit subtracts. The bank has no cached balance. */
    private static Map<AccountId, Long> tally(List<NewEntry> entries) {
        Map<AccountId, Long> tally = new HashMap<>();
        for (NewEntry entry : entries) {
            if (!entry.accountId().equals(BANK)) {
                long signed = entry.direction() == Direction.CREDIT
                        ? entry.amount().minorUnits()
                        : -entry.amount().minorUnits();
                tally.merge(entry.accountId(), signed, Long::sum);
            }
        }
        return tally;
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
