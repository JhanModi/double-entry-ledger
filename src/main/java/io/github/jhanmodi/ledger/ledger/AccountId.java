package io.github.jhanmodi.ledger.ledger;

import java.util.Objects;
import java.util.UUID;

/** Identifies an account. A distinct type, so an account id can't be passed where another kind of id is expected. */
public record AccountId(UUID value) implements Comparable<AccountId> {

    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Orders ids the way Postgres orders UUIDs: as unsigned bytes. {@link UUID#compareTo} compares signed numbers and
     * disagrees with Postgres for ids whose first bit is set. Postings lock accounts in ascending id order to prevent
     * deadlocks (ADR-0005): the lock query sorts in SQL, and {@code BalanceChanges} lists the same accounts in Java, so
     * Java and SQL must agree on what "ascending" means.
     */
    @Override
    public int compareTo(AccountId other) {
        int high = Long.compareUnsigned(value.getMostSignificantBits(), other.value.getMostSignificantBits());
        if (high != 0) {
            return high;
        }
        return Long.compareUnsigned(value.getLeastSignificantBits(), other.value.getLeastSignificantBits());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
