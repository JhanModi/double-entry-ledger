package io.github.jhanmodi.ledger.ledger;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a posting does to each customer account's cached balance, and whether those accounts can take it. Pure: no
 * database and no framework, so every rule here is unit-tested.
 *
 * <p>Only customer accounts appear here. System accounts have no cached balance, may go negative, and are never locked
 * (ADR-0004, ADR-0005).
 */
final class BalanceChanges {

    // Ascending account id order (AccountId compares the way Postgres does), which is also the order accounts are
    // locked in.
    private final SortedMap<AccountId, Long> deltas;

    private BalanceChanges(SortedMap<AccountId, Long> deltas) {
        this.deltas = deltas;
    }

    /**
     * Nets each customer account's entries into one change to its posted balance. An entry on the account's normal side
     * raises the balance and one on the other side lowers it (ADR-0003).
     *
     * @param accounts every account the entries name
     */
    static BalanceChanges of(List<NewEntry> entries, Map<AccountId, Account> accounts) {
        SortedMap<AccountId, Long> deltas = new TreeMap<>();
        for (NewEntry entry : entries) {
            Account account = accounts.get(entry.accountId());
            if (account == null) {
                throw new IllegalArgumentException("no account given for entry on " + entry.accountId());
            }
            if (account.kind() == AccountKind.CUSTOMER) {
                long amount = entry.amount().minorUnits();
                long change = entry.direction() == account.type().normalSide() ? amount : Math.negateExact(amount);
                deltas.merge(account.id(), change, Math::addExact);
            }
        }
        return new BalanceChanges(deltas);
    }

    /**
     * The customer accounts to lock, in ascending id order. Every posting locks in this one order, so two postings can
     * never each hold a lock the other is waiting for. Includes an account whose entries cancel out: its status must
     * still be checked, because entries are written to it.
     */
    SortedSet<AccountId> accountsToLock() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(deltas.keySet()));
    }

    /** Each customer account's net change to its posted balance, in ascending id order. Zero if its entries cancel. */
    SortedMap<AccountId, Long> deltas() {
        return Collections.unmodifiableSortedMap(deltas);
    }

    /**
     * Throws unless every account is open and none would end with an available balance (posted minus held) below zero.
     * Called with the values read under the accounts' locks, so the answer can't go stale before the posting is
     * written. Statuses are checked first, so a closed account is reported as closed even if it's also short of funds.
     *
     * @param locked the state of every account in {@link #accountsToLock()}, read under its lock
     * @throws AccountClosedException an account is closed
     * @throws InsufficientFundsException an account would go below zero
     */
    void requirePostable(Collection<LockedAccount> locked) {
        Map<AccountId, LockedAccount> byId =
                locked.stream().collect(Collectors.toMap(LockedAccount::id, Function.identity()));
        for (AccountId id : deltas.keySet()) {
            if (stateOf(byId, id).status() != AccountStatus.OPEN) {
                throw new AccountClosedException(id);
            }
        }
        deltas.forEach((id, delta) -> {
            LockedAccount account = stateOf(byId, id);
            long availableAfter =
                    Math.subtractExact(Math.addExact(account.postedBalance(), delta), account.heldBalance());
            if (availableAfter < 0) {
                throw new InsufficientFundsException(id);
            }
        });
    }

    private static LockedAccount stateOf(Map<AccountId, LockedAccount> locked, AccountId id) {
        LockedAccount account = locked.get(id);
        if (account == null) {
            // A programming error: the caller must lock every account in accountsToLock().
            throw new IllegalStateException("account " + id + " wasn't locked before the check");
        }
        return account;
    }
}
